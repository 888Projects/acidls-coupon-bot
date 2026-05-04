package com.acidcouponbot.service;

import java.util.Arrays;
import java.util.List;

public enum CouponCategory {

    GROCERIES("🛒", "Groceries",
        "mealie meal","maize","flour","bread","rice","sugar","cooking oil","oil",
        "pasta","noodles","samp","beans","milk","eggs","cheese","butter","cereal",
        "oats","juice","tea","coffee","food","grocery","fruit","vegetable"),

    TOILETRIES("🧼", "Toiletries",
        "soap","shampoo","conditioner","toothpaste","toothbrush","deodorant",
        "roll on","body wash","lotion","petroleum jelly","vaseline","sanitary",
        "pad","tampon","tissue","toilet paper","hand wash","sanitiser","body spray"),

    BABY("👶", "Baby & Kids",
        "baby","nappy","nappies","diaper","formula","nan","infacare",
        "nestum","cerelac","purity","infant","toddler","pampers"),

    HOUSEHOLD("🏠", "Household",
        "washing powder","laundry","dishwashing","bleach","cleaning","detergent",
        "softener","candle","matches","bulb","battery","broom","mop","bin bag"),

    DATA("📱", "Data & Airtime",
        "data","airtime","vodacom","mtn","telkom","cell c","bundle","gb","recharge");

    public final String emoji;
    public final String displayName;
    private final List<String> keywords;

    CouponCategory(String emoji, String displayName, String... keywords) {
        this.emoji = emoji;
        this.displayName = displayName;
        this.keywords = Arrays.asList(keywords);
    }

    public static CouponCategory from(String name, String product) {
        String combined = ((name != null ? name : "") + " "
            + (product != null ? product : "")).toLowerCase();
        for (CouponCategory cat : values())
            for (String kw : cat.keywords)
                if (combined.contains(kw)) return cat;
        return GROCERIES;
    }

    public static CouponCategory fromMenuNumber(String input) {
        if (input == null) return null;
        try {
            int idx = Integer.parseInt(input.trim()) - 1;
            CouponCategory[] v = values();
            if (idx >= 0 && idx < v.length) return v[idx];
        } catch (NumberFormatException ignored) {}
        String lower = input.trim().toLowerCase();
        for (CouponCategory cat : values())
            if (cat.displayName.toLowerCase().contains(lower)
                    || lower.contains(cat.name().toLowerCase())) return cat;
        return null;
    }

    public String menuNumber() { return String.valueOf(ordinal() + 1); }
}