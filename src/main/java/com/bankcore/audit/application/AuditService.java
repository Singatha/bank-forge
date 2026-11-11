package com.bankcore.audit.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bankcore.audit.domain.AuditAction;
import com.bankcore.audit.domain.AuditLog;
import com.bankcore.audit.domain.AuditResourceType;
import com.bankcore.audit.infrastructure.AuditLogRepository;
import com.bankcore.shared.web.RequestContext;
import com.bankcore.shared.web.RequestContext.RequestIdentifiers;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final RequestContext requestContext;
    private final Clock clock;

    public AuditService(AuditLogRepository auditLogRepository, RequestContext requestContext, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.requestContext = requestContext;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorUserId, AuditAction action, AuditResourceType resourceType,
                       UUID resourceId, Map<String, String> metadata) {
        save(actorUserId, action, resourceType, resourceId, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(UUID actorUserId, AuditAction action, AuditResourceType resourceType,
                                  UUID resourceId, Map<String, String> metadata) {
        save(actorUserId, action, resourceType, resourceId, metadata);
    }

    private void save(UUID actorUserId, AuditAction action, AuditResourceType resourceType,
                      UUID resourceId, Map<String, String> metadata) {
        RequestIdentifiers identifiers = requestContext.identifiers();
        AuditLog auditLog = new AuditLog(
                UUID.randomUUID(),
                actorUserId,
                action,
                resourceType,
                resourceId,
                identifiers.requestId(),
                identifiers.correlationId(),
                Instant.now(clock),
                metadata);
        auditLogRepository.save(auditLog);
    }
}
