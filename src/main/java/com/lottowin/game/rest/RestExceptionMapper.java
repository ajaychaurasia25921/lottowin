package com.lottowin.game.rest;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@Provider
public class RestExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(RestExceptionMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        if (exception instanceof WebApplicationException webApplicationException) {
            int status = webApplicationException.getResponse().getStatus();
            return json(status, messageOrDefault(exception, "Request failed."));
        }

        if (exception instanceof ConstraintViolationException constraintViolationException) {
            String message = constraintViolationException.getConstraintViolations()
                    .stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .collect(Collectors.joining("; "));
            return json(Response.Status.BAD_REQUEST.getStatusCode(), message);
        }

        LOG.error("Unhandled REST exception", exception);
        return json(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), "Unexpected server error.");
    }

    private Response json(int status, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ErrorResponse.of(message, status))
                .build();
    }

    private String messageOrDefault(Exception exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback
                : exception.getMessage();
    }
}
