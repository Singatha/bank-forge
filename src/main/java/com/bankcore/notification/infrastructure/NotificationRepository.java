package com.bankcore.notification.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bankcore.notification.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
}
