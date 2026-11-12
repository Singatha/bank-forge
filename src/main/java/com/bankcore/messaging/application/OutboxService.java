package com.bankcore.messaging.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.messaging.domain.EventType;
import com.bankcore.messaging.domain.OutboxEvent;
import com.bankcore.messaging.infrastructure.OutboxEventRepository;
import com.bankcore.shared.web.RequestContext;
import com.bankcore.transfer.domain.Transfer;

@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final RequestContext requestContext;

    public OutboxService(OutboxEventRepository repository, RequestContext requestContext) {
        this.repository = repository;
        this.requestContext = requestContext;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent appendTransferEvent(Transfer transfer, EventType eventType, Instant occurredAt) {
        Map<String, String> payload = new HashMap<>();
        payload.put("reference", transfer.getReference());
        payload.put("sourceAccountId", transfer.getSourceAccountId().toString());
        payload.put("destinationAccountId", transfer.getDestinationAccountId().toString());
        payload.put("initiatedByUserId", transfer.getInitiatedByUserId().toString());
        payload.put("amount", transfer.getAmount().toPlainString());
        payload.put("currency", transfer.getCurrency().name());
        payload.put("status", transfer.getStatus().name());
        if (transfer.getFailureReason() != null) {
            payload.put("failureReason", transfer.getFailureReason());
        }

        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(),
                eventType,
                transfer.getId(),
                occurredAt,
                requestContext.identifiers().correlationId(),
                payload);
        return repository.save(event);
    }
}
