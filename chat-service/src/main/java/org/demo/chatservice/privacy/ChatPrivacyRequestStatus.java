package org.demo.chatservice.privacy;

import java.time.Instant;
import java.util.UUID;

/** Safe receipt only; no message, participant identity, token or account association. */
public record ChatPrivacyRequestStatus(int schemaVersion,UUID requestId,String conversationId,String state,Instant requestedAt,boolean contentErased,boolean complete){}
