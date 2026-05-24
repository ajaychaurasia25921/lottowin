package com.lottowin.game.rest;

import com.lottowin.game.entity.PaymentTransaction;
import com.lottowin.game.entity.UserWallet;
import com.lottowin.game.entity.WalletLedgerEntry;
import com.lottowin.game.service.PaymentLockRegistry;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.Utils;
import io.quarkus.runtime.configuration.ProfileManager;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.json.JSONObject;

@Path("/payments")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("user")
@Blocking
public class PaymentResource {

    private static final Logger LOG = Logger.getLogger(PaymentResource.class);
    private static final long PAISE_PER_COIN = 20L;

    @Inject JsonWebToken jwt;
    @Inject PaymentLockRegistry paymentLockRegistry;

    @ConfigProperty(name = "razorpay.key-id")
    String razorpayKeyId;

    @ConfigProperty(name = "razorpay.key-secret")
    String razorpayKeySecret;

    @ConfigProperty(name = "razorpay.sandbox-mode", defaultValue = "true")
    boolean sandboxMode;

    @ConfigProperty(name = "razorpay.dev-mock-enabled", defaultValue = "false")
    boolean devMockEnabled;

    @ConfigProperty(name = "lottowin.dev-security-bypass", defaultValue = "false")
    boolean devSecurityBypass;

    @ConfigProperty(name = "lottowin.dev-user-id", defaultValue = "dev-user")
    String devUserId;

    @ConfigProperty(name = "lottowin.payments.max-topup-coins", defaultValue = "100000")
    long maxTopUpCoins;

    @POST
    @Path("/order")
    public PaymentOrderResponse createOrder(@Valid CoinTopUpRequest request) throws Exception {
        validateCoins(request.coins());

        String userId = currentUserId();
        long amountPaise = Math.multiplyExact(request.coins(), PAISE_PER_COIN);
        String receipt = "topup_" + UUID.randomUUID();

        if (sandboxMode) {
            LOG.infof("Razorpay sandbox mode is active for user %s, coins=%d, amountPaise=%d", userId, request.coins(), amountPaise);
        }

        String orderId;
        if (isDevMockPaymentEnabled()) {
            // Dev mock mode never calls Razorpay, which keeps local tests away from real payment rails.
            orderId = "order_dev_" + UUID.randomUUID().toString().replace("-", "");
        } else {
            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amountPaise);
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", receipt);
            orderRequest.put("payment_capture", 1);
            orderRequest.put("notes", new JSONObject()
                    .put("user_id", userId)
                    .put("coins", request.coins())
                    .put("sandbox", sandboxMode));

            RazorpayClient razorpayClient = new RazorpayClient(razorpayKeyId, razorpayKeySecret);
            Order order = razorpayClient.orders.create(orderRequest);
            orderId = order.get("id");
        }

        PaymentTransaction transaction = new PaymentTransaction();
        transaction.userId = userId;
        transaction.razorpayOrderId = orderId;
        transaction.coins = request.coins();
        transaction.amountPaise = amountPaise;
        transaction.sandbox = sandboxMode;
        transaction.receipt = receipt;
        transaction.persist();

        return new PaymentOrderResponse(
                orderId,
                razorpayKeyId,
                request.coins(),
                amountPaise,
                "INR",
                receipt,
                sandboxMode,
                new SignaturePayload(orderId, amountPaise, "INR"));
    }

    @POST
    @Path("/verify")
    public PaymentVerificationResponse verifyPayment(@Valid PaymentVerificationRequest request) throws Exception {
        String userId = currentUserId();

        synchronized (paymentLockRegistry.lockFor(request.razorpayOrderId())) {
            PaymentTransaction transaction = PaymentTransaction
                    .find("razorpayOrderId = ?1 and userId = ?2", request.razorpayOrderId(), userId)
                    .firstResult();

            if (transaction == null) {
                throw new WebApplicationException("Payment order was not found for the authenticated user.", Response.Status.NOT_FOUND);
            }
            if (PaymentTransaction.STATUS_VERIFIED.equals(transaction.status)) {
                return new PaymentVerificationResponse(true, transaction.coins, UserWallet.findOrCreate(userId, 0).balanceCoins);
            }

            // Production uses Razorpay's HMAC utility; dev accepts one explicit fake signature.
            boolean validSignature = isDevMockPaymentEnabled()
                    ? "dev-valid-signature".equals(request.razorpaySignature())
                    : verifyRazorpaySignature(request);

            if (!validSignature) {
                throw new WebApplicationException("Invalid Razorpay signature.", Response.Status.BAD_REQUEST);
            }

            UserWallet wallet = UserWallet.findOrCreate(userId, 0);
            wallet.credit(transaction.coins);
            WalletLedgerEntry.record(
                    userId,
                    transaction.coins,
                    wallet.balanceCoins,
                    "CREDIT",
                    "PAYMENT_TOPUP",
                    transaction.razorpayOrderId);

            transaction.razorpayPaymentId = request.razorpayPaymentId();
            transaction.status = PaymentTransaction.STATUS_VERIFIED;
            transaction.verifiedAt = Instant.now();
            transaction.update();

            return new PaymentVerificationResponse(true, transaction.coins, wallet.balanceCoins);
        }
    }

    private boolean verifyRazorpaySignature(PaymentVerificationRequest request) throws Exception {
        JSONObject options = new JSONObject();
        options.put("razorpay_order_id", request.razorpayOrderId());
        options.put("razorpay_payment_id", request.razorpayPaymentId());
        options.put("razorpay_signature", request.razorpaySignature());
        return Utils.verifyPaymentSignature(options, razorpayKeySecret);
    }

    private void validateCoins(long coins) {
        if (coins <= 0) {
            throw new WebApplicationException("coins must be greater than zero.", Response.Status.BAD_REQUEST);
        }
        if (coins > maxTopUpCoins) {
            throw new WebApplicationException("coins exceeds the configured maximum top-up limit.", Response.Status.BAD_REQUEST);
        }
    }

    private String currentUserId() {
        if (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            return jwt.getSubject();
        }
        if (devSecurityBypass && "dev".equals(ProfileManager.getActiveProfile())) {
            return devUserId;
        }
        throw new WebApplicationException("JWT subject is required.", Response.Status.UNAUTHORIZED);
    }

    private boolean isDevMockPaymentEnabled() {
        return devMockEnabled && "dev".equals(ProfileManager.getActiveProfile());
    }

    public record CoinTopUpRequest(@JsonProperty("coins") @Min(1) long coins) {}

    public record PaymentVerificationRequest(
            @JsonProperty("razorpay_order_id")
            @NotBlank
            String razorpayOrderId,
            @JsonProperty("razorpay_payment_id")
            @NotBlank
            String razorpayPaymentId,
            @JsonProperty("razorpay_signature")
            @NotBlank
            String razorpaySignature) {}

    public record SignaturePayload(
            @JsonProperty("razorpay_order_id")
            String razorpayOrderId,
            @JsonProperty("amount_paise")
            long amountPaise,
            @JsonProperty("currency")
            String currency) {}

    public record PaymentOrderResponse(
            @JsonProperty("razorpay_order_id")
            String razorpayOrderId,
            @JsonProperty("razorpay_key_id")
            String razorpayKeyId,
            @JsonProperty("coins")
            long coins,
            @JsonProperty("amount_paise")
            long amountPaise,
            @JsonProperty("currency")
            String currency,
            @JsonProperty("receipt")
            String receipt,
            @JsonProperty("sandbox")
            boolean sandbox,
            @JsonProperty("signature_payload")
            SignaturePayload signaturePayload) {}

    public record PaymentVerificationResponse(
            @JsonProperty("verified")
            boolean verified,
            @JsonProperty("credited_coins")
            long creditedCoins,
            @JsonProperty("wallet_balance_coins")
            long walletBalanceCoins) {}
}
