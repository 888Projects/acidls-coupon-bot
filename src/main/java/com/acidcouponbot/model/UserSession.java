package com.acidcouponbot.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_sessions")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class UserSession {

    @Id
    @Column(name = "phone_number")
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    @Builder.Default
    private SessionState state = SessionState.IDLE;

    @Column(name = "selected_category")
    private String selectedCategory;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    public enum SessionState {
        IDLE,
        AWAITING_CATEGORY_SELECTION,
        AWAITING_BUNDLE_CONFIRMATION,
        AWAITING_HISTORY_CHOICE
    }

    public boolean isExpired() {
        return lastUpdated != null
            && lastUpdated.isBefore(LocalDateTime.now().minusMinutes(10));
    }

    @PrePersist @PreUpdate
    protected void touch() { lastUpdated = LocalDateTime.now(); }
}