package com.adrianrevilla.trading.application.read;

/**
 * Step one of the switch to PROD: a one-use challenge that expires. The person must send it back with the MFA code and
 * the confirmation phrase before {@code expiresAt} (ISO-8601).
 */
public record ProdChallengeView(String challengeId, String expiresAt, String confirmPhrase) {
}
