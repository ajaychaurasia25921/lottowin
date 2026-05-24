package com.lottowin.game.service;

import com.lottowin.game.entity.GameBoard;
import com.lottowin.game.entity.UserWallet;
import com.lottowin.game.grpc.BoardStateReply;
import com.lottowin.game.grpc.ChooseCardRequest;
import com.lottowin.game.grpc.GetBoardStateRequest;
import com.lottowin.game.grpc.JoinBoardRequest;
import com.lottowin.game.grpc.MutinyGameBoardServiceGrpc;
import com.lottowin.game.grpc.PlayerAssignment;
import com.lottowin.game.grpc.SwapCardRequest;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.quarkus.runtime.configuration.ProfileManager;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.config.ConfigMapping;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

@GrpcService
@ApplicationScoped
@Blocking
@RolesAllowed("user")
public class GameBoardServiceImpl extends MutinyGameBoardServiceGrpc.GameBoardServiceImplBase {

    private static final Logger LOG = Logger.getLogger(GameBoardServiceImpl.class);

    // Timed board transitions are kept off the event loop because Panache MongoDB calls are blocking.
    private final ScheduledExecutorService lifecycleExecutor = Executors.newScheduledThreadPool(
            4,
            runnable -> {
                Thread thread = new Thread(runnable, "gameboard-lifecycle-worker");
                thread.setDaemon(true);
                return thread;
            });

    @Inject JsonWebToken jwt;
    @Inject BoardLockRegistry lockRegistry;
    @Inject GameBoardSecurityConfig securityConfig;

    @Override
    public Uni<BoardStateReply> joinBoard(JoinBoardRequest request) {
        return Uni.createFrom().item(() -> {
            String userId = currentUserId();
            long walletBalanceFromJwt = currentWalletBalanceFromJwt();
            validateJoinRequest(request);

            synchronized (lockRegistry.lockFor(request.getBoardId())) {
                // The per-board lock protects this JVM from double joins or duplicate card claims.
                GameBoard board = findOrCreateBoard(request);
                ensureState(board, GameBoard.STATE_OPEN);

                if (board.hasPlayer(userId)) {
                    return toReply(board, "Player is already on this board.");
                }
                if (board.isFull()) {
                    throw failedPrecondition("Board is already full.");
                }

                // JWT supplies the authenticated balance claim; Mongo stores the mutable ledger snapshot.
                UserWallet wallet = UserWallet.findOrCreate(userId, walletBalanceFromJwt);
                if (walletBalanceFromJwt < board.entryFeeCoins || wallet.balanceCoins < board.entryFeeCoins) {
                    throw failedPrecondition("Insufficient wallet balance for entry fee.");
                }

                wallet.debit(board.entryFeeCoins);
                board.players.add(userId);
                board.totalPoolCoins += board.entryFeeCoins;

                if (board.isFull()) {
                    startCardSelection(board);
                }

                board.update();
                return toReply(board, "Player joined successfully.");
            }
        });
    }

    @Override
    public Uni<BoardStateReply> chooseCard(ChooseCardRequest request) {
        return Uni.createFrom().item(() -> {
            String userId = currentUserId();

            synchronized (lockRegistry.lockFor(request.getBoardId())) {
                GameBoard board = requireBoard(request.getBoardId());
                ensureState(board, GameBoard.STATE_CARD_SELECTION);
                ensurePlayer(board, userId);

                if (Instant.now().isAfter(board.cardSelectionEndsAt)) {
                    throw failedPrecondition("Card selection window has expired.");
                }
                if (board.hasAssignment(userId)) {
                    throw failedPrecondition("Player has already selected a card.");
                }
                if (!board.availableNumbers.remove(Integer.valueOf(request.getNumber()))) {
                    throw failedPrecondition("Requested number is not available.");
                }

                board.playerAssignments.put(userId, request.getNumber());
                board.update();
                return toReply(board, "Card selected successfully.");
            }
        });
    }

    @Override
    public Uni<BoardStateReply> swapCard(SwapCardRequest request) {
        return Uni.createFrom().item(() -> {
            String userId = currentUserId();

            synchronized (lockRegistry.lockFor(request.getBoardId())) {
                GameBoard board = requireBoard(request.getBoardId());
                ensureState(board, GameBoard.STATE_CARD_SWAP);
                ensurePlayer(board, userId);

                if (Instant.now().isAfter(board.cardSwapEndsAt)) {
                    throw failedPrecondition("Card swap window has expired.");
                }
                Integer oldNumber = board.playerAssignments.get(userId);
                if (oldNumber == null) {
                    throw failedPrecondition("Player has no assigned card to swap.");
                }
                if (!board.availableNumbers.remove(Integer.valueOf(request.getNewNumber()))) {
                    throw failedPrecondition("Requested swap number is not available.");
                }

                board.availableNumbers.add(oldNumber);
                board.availableNumbers.sort(Comparator.naturalOrder());
                board.playerAssignments.put(userId, request.getNewNumber());
                board.update();
                return toReply(board, "Card swapped successfully.");
            }
        });
    }

    @Override
    public Uni<BoardStateReply> getBoardState(GetBoardStateRequest request) {
        return Uni.createFrom().item(() -> toReply(requireBoard(request.getBoardId()), "Board state loaded."));
    }

    private GameBoard findOrCreateBoard(JoinBoardRequest request) {
        GameBoard existing = findBoard(request.getBoardId()).orElse(null);
        if (existing != null) {
            if (existing.capacity != request.getCapacity() || existing.entryFeeCoins != request.getEntryFeeCoins()) {
                throw failedPrecondition("Existing board capacity or entry fee does not match the join request.");
            }
            return existing;
        }

        GameBoard board = new GameBoard();
        board.id = new ObjectId(request.getBoardId());
        board.capacity = request.getCapacity();
        board.entryFeeCoins = request.getEntryFeeCoins();
        board.persist();
        return board;
    }

    private Optional<GameBoard> findBoard(String boardId) {
        if (!ObjectId.isValid(boardId)) {
            throw invalidArgument("board_id must be a valid MongoDB ObjectId.");
        }
        return Optional.ofNullable(GameBoard.findById(new ObjectId(boardId)));
    }

    private GameBoard requireBoard(String boardId) {
        return findBoard(boardId).orElseThrow(() -> Status.NOT_FOUND
                .withDescription("Board was not found.")
                .asRuntimeException());
    }

    private void startCardSelection(GameBoard board) {
        board.state = GameBoard.STATE_CARD_SELECTION;
        board.cardSelectionStartedAt = Instant.now();
        board.cardSelectionEndsAt = board.cardSelectionStartedAt.plus(Duration.ofMinutes(1));
        board.availableNumbers = generateCardPool(board.capacity);

        if (!board.cardSelectionTimerScheduled) {
            board.cardSelectionTimerScheduled = true;
            // Exactly one delayed task owns the fallback from manual card selection to card swap.
            lifecycleExecutor.schedule(
                    () -> safelyFinalizeCardSelection(board.id.toString()),
                    1,
                    TimeUnit.MINUTES);
        }
    }

    private void safelyFinalizeCardSelection(String boardId) {
        try {
            synchronized (lockRegistry.lockFor(boardId)) {
                GameBoard board = requireBoard(boardId);
                if (!GameBoard.STATE_CARD_SELECTION.equals(board.state)) {
                    return;
                }

                for (String player : board.players) {
                    if (!board.playerAssignments.containsKey(player) && !board.availableNumbers.isEmpty()) {
                        int randomIndex = ThreadLocalRandom.current().nextInt(board.availableNumbers.size());
                        Integer autoAssigned = board.availableNumbers.remove(randomIndex);
                        board.playerAssignments.put(player, autoAssigned);
                    }
                }

                board.state = GameBoard.STATE_CARD_SWAP;
                board.cardSwapStartedAt = Instant.now();
                board.cardSwapEndsAt = board.cardSwapStartedAt.plus(Duration.ofSeconds(20));

                if (!board.cardSwapTimerScheduled) {
                    board.cardSwapTimerScheduled = true;
                    lifecycleExecutor.schedule(
                            () -> safelyCompleteDraw(boardId),
                            20,
                            TimeUnit.SECONDS);
                }

                board.update();
            }
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Failed to finalize card selection for board %s", boardId);
        }
    }

    private void safelyCompleteDraw(String boardId) {
        try {
            synchronized (lockRegistry.lockFor(boardId)) {
                GameBoard board = requireBoard(boardId);
                if (!GameBoard.STATE_CARD_SWAP.equals(board.state)) {
                    return;
                }

                board.state = GameBoard.STATE_DRAWING;
                List<Map.Entry<String, Integer>> assignments = new ArrayList<>(board.playerAssignments.entrySet());
                if (assignments.isEmpty()) {
                    throw failedPrecondition("Cannot draw a board without assignments.");
                }

                Map.Entry<String, Integer> winner = assignments.get(ThreadLocalRandom.current().nextInt(assignments.size()));
                board.winnerUserId = winner.getKey();
                board.winningNumber = winner.getValue();
                board.platformFeeCoins = Math.floorDiv(board.totalPoolCoins * 15, 100);
                board.winnerPayoutCoins = board.totalPoolCoins - board.platformFeeCoins;
                board.completedAt = Instant.now();
                board.state = GameBoard.STATE_COMPLETED;

                // Payouts are represented as wallet ledger updates so payment top-ups and game winnings share storage.
                UserWallet winnerWallet = UserWallet.findOrCreate(board.winnerUserId, 0);
                winnerWallet.credit(board.winnerPayoutCoins);

                UserWallet corporateWallet = UserWallet.findOrCreate("corporate-wallet", 0);
                corporateWallet.credit(board.platformFeeCoins);

                board.update();
            }
        } catch (RuntimeException exception) {
            LOG.errorf(exception, "Failed to complete draw for board %s", boardId);
        }
    }

    private List<Integer> generateCardPool(int capacity) {
        List<Integer> numbers = new ArrayList<>();
        for (int number = 1; number <= capacity * 5; number++) {
            numbers.add(number);
        }
        Collections.shuffle(numbers);
        return numbers;
    }

    private void validateJoinRequest(JoinBoardRequest request) {
        if (!GameBoard.isSupportedCapacity(request.getCapacity())) {
            throw invalidArgument("Supported board capacities are exactly 5, 10, 15, or 20.");
        }
        if (request.getEntryFeeCoins() <= 0) {
            throw invalidArgument("entry_fee_coins must be greater than zero.");
        }
    }

    private void ensureState(GameBoard board, String requiredState) {
        if (!requiredState.equals(board.state)) {
            throw failedPrecondition("Board state must be " + requiredState + " but was " + board.state + ".");
        }
    }

    private void ensurePlayer(GameBoard board, String userId) {
        if (!board.hasPlayer(userId)) {
            throw Status.PERMISSION_DENIED
                    .withDescription("Authenticated user is not a player on this board.")
                    .asRuntimeException();
        }
    }

    private String currentUserId() {
        if (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            return jwt.getSubject();
        }
        if (isDevBypassEnabled()) {
            return securityConfig.devUserId();
        }
        throw Status.UNAUTHENTICATED
                .withDescription("JWT subject is required.")
                .asRuntimeException();
    }

    private long currentWalletBalanceFromJwt() {
        Object claim = jwt == null ? null : jwt.getClaim("wallet_balance");
        if (claim instanceof Number number) {
            return number.longValue();
        }
        if (claim instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        if (isDevBypassEnabled()) {
            return securityConfig.devWalletBalanceCoins();
        }
        throw Status.UNAUTHENTICATED
                .withDescription("JWT wallet_balance claim is required.")
                .asRuntimeException();
    }

    private boolean isDevBypassEnabled() {
        return securityConfig.devSecurityBypass() && "dev".equals(ProfileManager.getActiveProfile());
    }

    private BoardStateReply toReply(GameBoard board, String message) {
        BoardStateReply.Builder reply = BoardStateReply.newBuilder()
                .setBoardId(board.id == null ? "" : board.id.toString())
                .setState(board.state)
                .setCapacity(board.capacity)
                .setEntryFeeCoins(board.entryFeeCoins)
                .addAllPlayers(board.players)
                .addAllAvailableNumbers(board.availableNumbers)
                .setWinningNumber(board.winningNumber)
                .setWinnerUserId(board.winnerUserId == null ? "" : board.winnerUserId)
                .setTotalPoolCoins(board.totalPoolCoins)
                .setPlatformFeeCoins(board.platformFeeCoins)
                .setWinnerPayoutCoins(board.winnerPayoutCoins)
                .setMessage(message);

        board.playerAssignments.forEach((userId, number) -> reply.addAssignments(PlayerAssignment.newBuilder()
                .setUserId(userId)
                .setNumber(number)
                .build()));

        return reply.build();
    }

    private RuntimeException invalidArgument(String description) {
        return Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
    }

    private RuntimeException failedPrecondition(String description) {
        return Status.FAILED_PRECONDITION.withDescription(description).asRuntimeException();
    }

    @PreDestroy
    void shutdownExecutor() {
        lifecycleExecutor.shutdownNow();
    }

    @ConfigMapping(prefix = "lottowin")
    public interface GameBoardSecurityConfig {
        boolean devSecurityBypass();
        String devUserId();
        long devWalletBalanceCoins();
    }
}
