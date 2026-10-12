package com.example.viewcast.security;

import java.io.IOException;

/** Échec de l authentification ou du protocole (code de raison : voir constantes). */
public final class HandshakeException extends IOException {
    public static final int BAD_PROOF = 1;     // code PIN incorrect
    public static final int LOCKED = 2;        // trop d essais : nouveau code requis
    public static final int PROTOCOL = 3;      // pas un client ViewCast sécurisé / message invalide

    public final int reason;

    public HandshakeException(int reason, String message) {
        super(message);
        this.reason = reason;
    }
}
