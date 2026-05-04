package com.acidcouponbot.service;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("CouponCategory")
class CouponCategoryTest {

    @Test @DisplayName("mealie meal maps to GROCERIES")
    void mealeMeal_isGroceries() {
        assertThat(CouponCategory.from("Mealie Meal 5kg", null))
            .isEqualTo(CouponCategory.GROCERIES);
    }

    @Test @DisplayName("shampoo maps to TOILETRIES")
    void shampoo_isToiletries() {
        assertThat(CouponCategory.from("Sunsilk Shampoo 400ml", null))
            .isEqualTo(CouponCategory.TOILETRIES);
    }

    @Test @DisplayName("nappies maps to BABY")
    void nappies_isBaby() {
        assertThat(CouponCategory.from("Pampers Nappies Size 3", null))
            .isEqualTo(CouponCategory.BABY);
    }

    @Test @DisplayName("washing powder maps to HOUSEHOLD")
    void washingPowder_isHousehold() {
        assertThat(CouponCategory.from("Skip Washing Powder 2kg", null))
            .isEqualTo(CouponCategory.HOUSEHOLD);
    }

    @Test @DisplayName("data bundle maps to DATA")
    void dataBundle_isData() {
        assertThat(CouponCategory.from("Vodacom 1GB Data Bundle", null))
            .isEqualTo(CouponCategory.DATA);
    }

    @Test @DisplayName("unknown voucher defaults to GROCERIES")
    void unknown_defaultsToGroceries() {
        assertThat(CouponCategory.from("XYZ Unknown Product", null))
            .isEqualTo(CouponCategory.GROCERIES);
    }

    @Test @DisplayName("null inputs default to GROCERIES")
    void nullInputs_defaultToGroceries() {
        assertThat(CouponCategory.from(null, null))
            .isEqualTo(CouponCategory.GROCERIES);
    }

    @Test @DisplayName("fromMenuNumber '1' returns GROCERIES")
    void menuNumber1_isGroceries() {
        assertThat(CouponCategory.fromMenuNumber("1"))
            .isEqualTo(CouponCategory.GROCERIES);
    }

    @Test @DisplayName("fromMenuNumber '5' returns DATA")
    void menuNumber5_isData() {
        assertThat(CouponCategory.fromMenuNumber("5"))
            .isEqualTo(CouponCategory.DATA);
    }

    @Test @DisplayName("fromMenuNumber out of range returns null")
    void menuNumberOutOfRange_returnsNull() {
        assertThat(CouponCategory.fromMenuNumber("99")).isNull();
        assertThat(CouponCategory.fromMenuNumber("0")).isNull();
    }

    @Test @DisplayName("fromMenuNumber null returns null")
    void menuNumberNull_returnsNull() {
        assertThat(CouponCategory.fromMenuNumber(null)).isNull();
    }

    @Test @DisplayName("fromMenuNumber 'groceries' text matches")
    void menuNumberText_matchesName() {
        assertThat(CouponCategory.fromMenuNumber("Groceries"))
            .isEqualTo(CouponCategory.GROCERIES);
    }

    @Test @DisplayName("all 5 categories have emoji and displayName")
    void allCategories_haveEmojiAndDisplayName() {
        for (CouponCategory cat : CouponCategory.values()) {
            assertThat(cat.emoji).isNotBlank();
            assertThat(cat.displayName).isNotBlank();
            assertThat(cat.menuNumber()).isNotBlank();
        }
    }
}