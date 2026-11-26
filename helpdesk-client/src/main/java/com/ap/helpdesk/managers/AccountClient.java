package com.ap.helpdesk.managers;

import com.ap.helpdesk.classes.Administrator;
import com.ap.helpdesk.classes.Requester;
import com.ap.helpdesk.classes.Technician;
import com.ap.helpdesk.classes.User;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Sends REST requests and shares the signed-in session across accounts, tickets, and reports. */
final class AccountClient {
    private static final Logger LOG = LogManager.getLogger(AccountClient.class);
    private final URI server = URI.create(System.getProperty("helpdesk.server.url", "http://127.0.0.1:8080"));
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Gson json = new Gson();

    String serverHost() {
        return server.getHost();
    }

    /** Checks credentials and reads the authenticated profile from the server. */
    CompletableFuture<User> signIn(String email, String password, boolean technicianLogin) {
        Map<String, String> credentials;

        if (technicianLogin) {
            credentials = Map.of(
                    "email", email,
                    "password", password,
                    "requiredRole", "TECHNICIAN"
            );
        } else {
            credentials = Map.of("email", email, "password", password);
        }

        return post("/api/users/login", credentials).thenApply(this::readUser);
    }
    
    //Recover password with name, email and new password
    CompletableFuture<Void> recoverPassword(String fullName, String email, String newPassword) {
        return post("/api/users/recover-password", Map.of(
                "fullName", fullName,
                "email", email,
                "newPassword", newPassword
        )).thenAccept(body -> {});
    }

    /** Creates a requester account and receives the new session. */
    CompletableFuture<User> registerWithRole(String fullName, String email,
            String password, User.Role role, String setupCode) {

        return post("/api/users", Map.of(
                "fullName", fullName,
                "email", email,
                "password", password,
                "role", role.name(),
                "setupCode", setupCode == null ? "" : setupCode
        )).thenApply(this::readUser);
    }
    
    /** Loads the users shown in the administrator's account editor. */
    CompletableFuture<List<User>> listUsers() {
        return get("/api/users").thenApply(body -> {
            List<User> users = new ArrayList<>();
            for (var account : JsonParser.parseString(body).getAsJsonArray()) {
                users.add(readUser(account.getAsJsonObject()));
            }
            return users;
        });
    }

    /** Saves the selected role using the administrator's session. */
    CompletableFuture<User> changeRole(long userId, User.Role role) {
        return write("PATCH", "/api/users/" + userId + "/role",
                Map.of("role", role.name()))
                .thenApply(this::readUser);
    }

    /** Changes the signed-in user's password. */
    CompletableFuture<Void> changePassword(String currentPassword, String newPassword) {
        return write("PATCH", "/api/users/password", Map.of(
                "currentPassword", currentPassword, "newPassword", newPassword)).thenAccept(body -> {});
    }

    /** Replaces the selected account's password using the administrator's session. */
    CompletableFuture<Void> resetPassword(long userId, String newPassword) {
        return write("PATCH", "/api/users/" + userId + "/password", Map.of("newPassword", newPassword))
                .thenAccept(body -> {});
    }

    /** Signs out on the server before discarding our session cookie. */
    CompletableFuture<Void> signOut() {
        return post("/api/users/logout", null).thenAccept(body -> cookies.getCookieStore().removeAll());
    }

    /** Sends a POST through the signed-in session. */
    private CompletableFuture<String> post(String path, Map<String, String> data) {
        return write("POST", path, data);
    }

    /** Sends a GET with the same session cookie used to sign in. */
    CompletableFuture<String> get(String path) {
        return send(request(path).GET().build());
    }

    /** Adds a form token before sending a request that changes server data. */
    CompletableFuture<String> write(String method, String path, Map<String, ?> data) {
        return get("/api/users/csrf").thenCompose(body -> {
            JsonObject token = json.fromJson(body, JsonObject.class);
            HttpRequest.Builder request = request(path).header(
                    token.get("headerName").getAsString(), token.get("token").getAsString());
            // Sign-out still needs the form token even though it sends no JSON body.
            if (data == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.toJson(data)));
            }
            return send(request.build());
        });
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(server.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
    }

    private CompletableFuture<String> send(HttpRequest request) {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, failure) -> logFailure(request, response, failure)).thenApply(response -> {
            String detail = domainErrorDetail(response);
            if (detail != null) {
                throw new RequestFailure(detail);
            }
            switch (response.statusCode()) {
                case 200:
                case 201:
                case 204:
                    return response.body();
                case 400:
                    throw new RequestFailure("Check the information in the form and try again.");
                case 401:
                    throw new RequestFailure(request.uri().getPath().equals("/api/users/login")
                            ? "Invalid email or password."
                            : "Your session expired. Sign in again.");
                case 403:
	                	if (request.uri().getPath().equals("/api/users/login")) {
	                	    throw new RequestFailure("This account cannot sign in as a technician.");
	                	}
	                	if (request.uri().getPath().equals("/api/users")
	                	        && request.method().equals("POST")) {
	                	    throw new RequestFailure("Check the setup code for the selected account type.");
	                	}
	                	
                    throw new RequestFailure("The server declined this request. Please try again.");
                case 404:
                    if (request.uri().getPath().equals("/api/reports/tickets/details")) {
                        throw new RequestFailure("Report details require the updated help desk server.");
                    }
                    throw new RequestFailure(request.method().equals("PATCH") && request.uri().getPath().endsWith("/role")
                            ? "That account is no longer available."
                            : "The ticket or technician is no longer available.");
                case 422:
	                	if (request.uri().getPath().equals("/api/users/recover-password")) {
	                	    throw new RequestFailure("The full name and email do not match an account.");
	                	}
	                	
                    throw new RequestFailure(request.uri().getPath().equals("/api/users/password")
                            ? "Current password is incorrect."
                            : "Check the information in the form and try again.");
                case 409:
                    String path = request.uri().getPath();
                    if (path.endsWith("/role")) {
                        throw new RequestFailure("Resolve the technician's active assignments before changing their role.");
                    }
                    throw new RequestFailure(path.equals("/api/users") ? "That email is already registered."
                            : "The ticket's current status prevents this action. Refresh and try again.");
                default:
                    throw new RequestFailure("The server could not complete the request.");
            }
        });
    }

    /** Reads a public problem detail for a missing resource or an assignment conflict. */
    private static String domainErrorDetail(HttpResponse<String> response) {
        int status = response.statusCode();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if ((status != 404 && status != 409) || !contentType.startsWith("application/problem+json")) {
            return null;
        }
        try {
            JsonObject problem = JsonParser.parseString(response.body()).getAsJsonObject();
            var detail = problem.get("detail");
            return detail != null && detail.isJsonPrimitive() && detail.getAsJsonPrimitive().isString()
                    && !detail.getAsString().isBlank() ? detail.getAsString() : null;
        } catch (JsonParseException | IllegalStateException failure) {
            return null;
        }
    }

    private static void logFailure(HttpRequest request, HttpResponse<String> response, Throwable failure) {
        String path = request.uri().getPath();
        if (path.equals("/api/notifications/subscription")) {
            // The socket listener logs the outage once while it retries.
            return;
        }
        if (failure != null) {
            LOG.error("Could not reach the server for {} {}", request.method(), path, failure);
        } else if (response.statusCode() >= 500) {
            LOG.error("{} {} returned {}", request.method(), path, response.statusCode());
        } else if (response.statusCode() >= 400) {
            LOG.warn("{} {} returned {}", request.method(), path, response.statusCode());
        }
    }

    /** Uses the server's role to select the matching client account type. */
    private User readUser(String body) {
        return readUser(json.fromJson(body, JsonObject.class));
    }

    private User readUser(JsonObject account) {
        long id = account.get("userId").getAsLong();
        String name = account.get("fullName").getAsString();
        String email = account.get("email").getAsString();
        User.Role role = User.Role.valueOf(account.get("role").getAsString());

        return switch (role) {
            case STUDENT, STAFF -> new Requester(id, name, email, role);
            case TECHNICIAN -> new Technician(id, name, email);
            case ADMINISTRATOR -> new Administrator(id, name, email);
        };
    }

    /** Converts request failures into messages that the forms and dialogs can display. */
    static String messageFor(Throwable failure) {
        Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
        if (cause instanceof RequestFailure) {
            return cause.getMessage();
        }
        if (cause instanceof java.io.IOException) {
            return "Could not reach the help desk server. Check that it is running.";
        }
        return "The server returned an unexpected response.";
    }

    private static final class RequestFailure extends RuntimeException {
        RequestFailure(String message) {
            super(message);
        }
    }
}
