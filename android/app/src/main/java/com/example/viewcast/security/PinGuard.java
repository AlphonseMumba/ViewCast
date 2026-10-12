package com.example.viewcast.security;

import java.security.SecureRandom;

/**
 * Code PIN à 6 chiffres et limite d essais.
 * Après {@code maxFailures} échecs d affilée, l appelant doit renouveler le PIN : les essais déjà
 * faits par un attaquant ne lui servent alors plus à rien.
 */
public final class PinGuard {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int maxFailures;
    private int failures = 0;

    public PinGuard(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    /** Nouveau code aléatoire de 6 chiffres (000000 à 999999). */
    public static String newPin() {
        return String.format("%06d", RANDOM.nextInt(1000000));
    }

    /** Vrai si la forme du code est valide (exactement 6 chiffres). */
    public static boolean isValid(String pin) {
        if (pin == null || pin.length() != 6) return false;
        for (int i = 0; i < 6; i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Enregistre un échec. Renvoie vrai si la limite est atteinte (=> renouveler le PIN). */
    public synchronized boolean recordFailure() {
        failures++;
        if (failures >= maxFailures) {
            failures = 0;
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess() {
        failures = 0;
    }

    public synchronized int failures() {
        return failures;
    }
}
