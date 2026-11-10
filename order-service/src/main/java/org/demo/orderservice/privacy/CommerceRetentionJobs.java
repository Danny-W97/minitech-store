package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.CommerceRetentionAssessment;
import org.demo.platformsecurity.privacy.CommerceRetentionFieldScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;

/**
 * Durable metadata store and bounded assessment executor. Deliberately not a
 * Spring component, scheduled worker, controller or business-data mutator.
 * Proof writes are for trusted, versioned service adapters only. No client or
 * operator request may be converted directly into a positive attestation.
 */
public final class CommerceRetentionJobs {
    public record Work(Subject subject, Phase phase, long proofRevision, UUID token) {
        public Work {
            Objects.requireNonNull(subject); Objects.requireNonNull(phase); Objects.requireNonNull(token);
            if (proofRevision < 0) throw new IllegalArgumentException("Invalid retention revision");
        }
    }
    public enum Receipt { STORED, IDEMPOTENT, STALE, CONFLICT }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CommerceRetentionHolds holds;
    // Quarantine increments the scope revision too. Once assessed as BLOCKED,
    // its unchanged flag must not trigger an immediate endless claim loop.
    private static final String HOLD_CHANGED="(coalesce(ho.revision,0)<>j.order_hold_revision OR coalesce(hc.revision,0)<>j.customer_hold_revision)";

    public CommerceRetentionJobs(DataSource source, PlatformTransactionManager manager) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(2);
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
        holds = new CommerceRetentionHolds(source,manager);
    }

    /** Does not infer reconciliation, create a hold clearance or reset any clock. */
    public Subject prepare(long order, Phase phase) { return prepareGeneration(order,phase,null,null); }

    /** Internal caller has validated the requested generation and predecessor. */
    Subject prepareGeneration(long order, Phase phase, UUID requestedGeneration, UUID predecessor) {
        if (order < 1) throw new IllegalArgumentException("Invalid retention order");
        Objects.requireNonNull(phase);
        return tx.execute(status -> {
            var owner = order(order);
            var holdSnapshot=holds.snapshot(order,customer(owner),phase);
            if(holdSnapshot.quarantined())throw new IllegalStateException("RETENTION_HOLD_QUARANTINED");
            // Only explicitly assessed purchases are tracked. Historical terminal
            // data gets no inferred clock; a tracked future close supplies it.
            jdbc.update("INSERT INTO commerce_retention_order_state(order_id,open_seen) SELECT ?,NOT commerce_retention_order_ready(?) ON CONFLICT(order_id) DO NOTHING",order,order);
            var rows = jdbc.queryForList("SELECT * FROM commerce_retention_jobs WHERE order_id=? AND current FOR UPDATE", order);
            if (!rows.isEmpty()) {
                var row = rows.getFirst();
                if (sameOrder(row, owner) && POLICY.equals(row.get("policy"))) {
                    if (!phase.name().equals(row.get("phase"))) throw new IllegalStateException("Retention phase requires a new generation");
                    return subject(row);
                }
                retire(order, (UUID) row.get("generation"), "ORDER_CHANGED");
            }
            UUID generation = requestedGeneration==null?UUID.randomUUID():requestedGeneration;
            jdbc.update("INSERT INTO commerce_retention_jobs(order_id,generation,customer_id,order_version,phase,policy,order_hold_revision,customer_hold_revision,preparation_predecessor) VALUES(?,?,?,?,?,?,?,?,?)",
                    order, generation, owner.get("customer_id"), owner.get("version"), phase.name(), POLICY,holdSnapshot.orderRevision(),holdSnapshot.customerRevision(),predecessor);
            return new Subject(order, customer(owner), number(owner, "version"), generation);
        });
    }

    public Receipt record(ChainProof proof) {
        Objects.requireNonNull(proof);
        return record(proof.subject(), proof.phase(), proof.policy(), "CHAIN", proof.participant().name(),
                proof.state(), proof.reconciledAt(), proof.redactedAt(),null);
    }

    public Receipt record(Guard guard, GuardProof proof) {
        Objects.requireNonNull(guard); Objects.requireNonNull(proof);
        if(guard==Guard.FIELD_SCOPE_REVIEWED && proof.state()==State.CONFIRMED)throw new IllegalArgumentException("Trusted field scope review required");
        if((guard==Guard.PRESERVATION_POLICY_REVIEWED || guard==Guard.INDEPENDENT_JOURNAL_CURRENT)
                && proof.state()==State.CONFIRMED)
            throw new IllegalArgumentException("Complete preservation policy and current independent journal evidence unavailable");
        return record(proof.subject(), proof.phase(), proof.policy(), "GUARD", guard.name(), proof.state(), null, null,null);
    }

    Receipt recordReviewedFieldScope(Subject subject,Phase phase,String definitionHash){
        if(!CommerceRetentionFieldScope.verifiedDigest().equals(definitionHash))throw new IllegalArgumentException("Unreviewed field scope");
        return record(subject,phase,POLICY,"GUARD",Guard.FIELD_SCOPE_REVIEWED.name(),State.CONFIRMED,null,null,definitionHash);
    }

    private Receipt record(Subject subject, Phase phase, String policy, String kind, String source,
                           State state, Instant reconciled, Instant redacted,String definitionHash) {
        if (!POLICY.equals(policy)) throw new IllegalArgumentException("Unreviewed retention policy");
        requireDatabasePrecision(reconciled); requireDatabasePrecision(redacted);
        return tx.execute(status -> {
            var owner = order(subject.orderId());
            var rows = jdbc.queryForList("SELECT * FROM commerce_retention_jobs WHERE order_id=? AND generation=? FOR UPDATE",
                    subject.orderId(), subject.chainGeneration());
            if (rows.isEmpty()) return Receipt.STALE;
            var row = rows.getFirst();
            if (!Boolean.TRUE.equals(row.get("current"))) return Receipt.STALE;
            if (!sameOrder(row, owner)) { retire(subject.orderId(), subject.chainGeneration(), "ORDER_CHANGED"); return Receipt.STALE; }
            if (!subject(row).equals(subject) || !phase.name().equals(row.get("phase")) || !policy.equals(row.get("policy"))) return Receipt.STALE;
            var previous = jdbc.queryForList("SELECT * FROM commerce_retention_evidence WHERE order_id=? AND generation=? AND phase=? AND kind=? AND source=?",
                    subject.orderId(), subject.chainGeneration(), phase.name(), kind, source);
            if (!previous.isEmpty()) {
                var existing = previous.getFirst();
                if (state.name().equals(existing.get("state")) && policy.equals(existing.get("policy"))
                        && Objects.equals(reconciled, instant(existing, "reconciled_at"))
                        && Objects.equals(redacted, instant(existing, "redacted_at"))
                        && Objects.equals(definitionHash,existing.get("field_scope_hash"))) return Receipt.IDEMPOTENT;
                // Never overwrite proof clocks or a previous hold in one generation.
                retire(subject.orderId(), subject.chainGeneration(), "EVIDENCE_CHANGED");
                return Receipt.CONFLICT;
            }
            jdbc.update("INSERT INTO commerce_retention_evidence(order_id,generation,phase,kind,source,policy,state,reconciled_at,redacted_at,field_scope_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    subject.orderId(), subject.chainGeneration(), phase.name(), kind, source, policy, state.name(), timestamp(reconciled), timestamp(redacted),definitionHash);
            jdbc.update("UPDATE commerce_retention_jobs SET proof_revision=proof_revision+1,state='WAITING_PROOF',attempts=0,run_token=NULL,run_until=NULL,next_attempt_at=CURRENT_TIMESTAMP,eligible_at=NULL,code=NULL,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND generation=?",
                    subject.orderId(), subject.chainGeneration());
            return Receipt.STORED;
        });
    }

    /** Retains old metadata. A late invalidation cannot retire a newer generation. */
    public boolean invalidate(long order, UUID generation) {
        if (order < 1 || generation == null) throw new IllegalArgumentException("Invalid retention generation");
        return Boolean.TRUE.equals(tx.execute(status -> {
            order(order);
            return retire(order, generation, "SOURCE_INVALIDATED") == 1;
        }));
    }

    /** Scoped source callback: stale/wrong phase or version cannot retire new work. */
    public boolean invalidate(long order, UUID generation, long version, Phase phase) {
        if (order < 1 || generation == null || version < 0 || phase == null) throw new IllegalArgumentException("Invalid retention scope");
        return Boolean.TRUE.equals(tx.execute(status -> {
            var owners = jdbc.queryForList("SELECT id,customer_id,version FROM orders WHERE id=? FOR NO KEY UPDATE", order);
            if (owners.isEmpty()) return false;
            var rows = jdbc.queryForList("SELECT * FROM commerce_retention_jobs WHERE order_id=? AND generation=? FOR UPDATE", order, generation);
            if (rows.isEmpty()) return false;
            var row = rows.getFirst();
            if (!Boolean.TRUE.equals(row.get("current")) || !POLICY.equals(row.get("policy"))
                    || !phase.name().equals(row.get("phase")) || version != number(row, "order_version")
                    || !sameOrder(row, owners.getFirst())) return false;
            return retire(order, generation, "SOURCE_INVALIDATED") == 1;
        }));
    }

    /** One short lease per call; no account scan or business-field access. */
    public Optional<Work> claim() { return claimScoped(null,null); }

    /** A targeted workflow must never lease another purchase. */
    public Optional<Work> claim(Subject subject,Phase phase) {
        Objects.requireNonNull(subject);Objects.requireNonNull(phase);return claimScoped(subject,phase);
    }
    private Optional<Work> claimScoped(Subject subject,Phase phase) {
        String scope=subject==null?"":" AND order_id=? AND generation=? AND order_version=? AND coalesce(customer_id,0)=? AND phase=?";
        var args=new ArrayList<Object>();
        if(subject!=null)args.addAll(java.util.List.of(subject.orderId(),subject.chainGeneration(),subject.orderVersion(),subject.customerId(),phase.name()));
        return tx.execute(status -> {
            jdbc.update(expirationSql(scope),args.toArray());
            var rows = jdbc.queryForList(selectionSql(scope),claimArguments(args));
            if (rows.isEmpty()) return Optional.empty();
            var row = rows.getFirst(); UUID token = UUID.randomUUID();
            jdbc.update("UPDATE commerce_retention_jobs SET state='LEASED',attempts=attempts+1,run_token=?,run_until=clock_timestamp()+INTERVAL '15 seconds',updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND generation=?",
                    token, row.get("order_id"), row.get("generation"));
            return Optional.of(new Work(subject(row), Phase.valueOf((String) row.get("phase")), number(row, "proof_revision"), token));
        });
    }

    /** Internal fresh-read workflow only. Preserve the retry budget and any
     * active lease; never turn a concurrent proof revision into new work. */
    Optional<Work> claimRevalidated(Subject subject,Phase phase,long proofRevision){
        Objects.requireNonNull(subject);Objects.requireNonNull(phase);
        if(proofRevision<0)throw new IllegalArgumentException("Invalid retention revision");
        return tx.execute(status->{
            var owners=jdbc.queryForList("SELECT id,customer_id,version FROM orders WHERE id=? FOR NO KEY UPDATE",subject.orderId());
            if(owners.isEmpty() || customer(owners.getFirst())!=subject.customerId() || number(owners.getFirst(),"version")!=subject.orderVersion())return Optional.empty();
            UUID token=UUID.randomUUID();
            int ready=jdbc.update("""
                UPDATE commerce_retention_jobs SET state='LEASED',attempts=attempts+1,run_token=?,
                run_until=clock_timestamp()+INTERVAL '15 seconds',next_attempt_at=statement_timestamp(),updated_at=CURRENT_TIMESTAMP
                WHERE order_id=? AND generation=? AND order_version=? AND coalesce(customer_id,0)=? AND phase=? AND policy=?
                AND current AND proof_revision=? AND attempts<8 AND (run_until IS NULL OR run_until<=clock_timestamp())
                AND (state IN ('DUE_FOR_REVIEW','BLOCKED','REVIEW') OR next_attempt_at<=clock_timestamp())
                """,token,subject.orderId(),subject.chainGeneration(),subject.orderVersion(),subject.customerId(),phase.name(),POLICY,proofRevision);
            return ready==1?Optional.of(new Work(subject,phase,proofRevision,token)):Optional.empty();
        });
    }

    static String expirationSql(String scope){return """
        UPDATE commerce_retention_jobs SET state='FAILED',code='RETRY_LIMIT',run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE (order_id,generation) IN (SELECT order_id,generation FROM commerce_retention_jobs
        WHERE current AND state='LEASED' AND attempts=8 AND run_until<=CURRENT_TIMESTAMP
        """+scope+" ORDER BY next_attempt_at,order_id LIMIT 25 FOR UPDATE SKIP LOCKED)";}

    static String selectionSql(String scope){return scope.isEmpty()?GLOBAL_SELECTION:scopedSelectionSql(scope);}

    // A primary-key restricted query retains exact scope/identity checks.
    static String scopedSelectionSql(String scope){
        return """
                    SELECT j.* FROM commerce_retention_jobs j
                    LEFT JOIN commerce_retention_hold_scopes ho ON ho.scope_type='ORDER' AND ho.subject_id=j.order_id
                    LEFT JOIN commerce_retention_hold_scopes hc ON hc.scope_type='CUSTOMER' AND hc.subject_id=j.customer_id
                    WHERE j.current AND j.policy=? AND j.attempts<8
                    AND j.state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED','DUE_FOR_REVIEW','BLOCKED','REVIEW')
                    AND (j.run_until IS NULL OR j.run_until<=clock_timestamp())
                    """+" AND (j.next_attempt_at<=clock_timestamp() OR "+HOLD_CHANGED+")"
                    +" AND (j.state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED') OR "+HOLD_CHANGED+")"
                    +scope+" ORDER BY j.next_attempt_at,j.order_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED";
    }

    /** Four disjoint reasons to wake work. Each branch locks at most one job;
     * the final choice leases one, releasing other candidate locks on commit.
     * Changed scopes use monotonic revisions; unchanged revision-zero headers
     * never enter the scan. A busy customer contributes at most 16 candidates.
     * Selection uses statement time for index ranges; lease ownership still
     * uses the live database clock. No database-wide constant-work claim. */
    private static final String GLOBAL_SELECTION="""
        WITH requested AS (SELECT ?::text AS policy), candidates AS MATERIALIZED (
          SELECT * FROM (
            SELECT j.order_id,j.generation,j.next_attempt_at FROM commerce_retention_jobs j
            WHERE j.current AND j.policy=(SELECT policy FROM requested) AND j.attempts<8
              AND j.state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED')
              AND j.next_attempt_at<=statement_timestamp()
              AND (j.run_until IS NULL OR j.run_until<=statement_timestamp())
            ORDER BY j.next_attempt_at,j.order_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
          ) due
          UNION ALL SELECT * FROM (
            SELECT j.order_id,j.generation,j.next_attempt_at FROM commerce_retention_jobs j
            JOIN (
              SELECT w.order_id,w.generation FROM commerce_retention_hold_scopes h
              CROSS JOIN LATERAL (
                SELECT x.order_id,x.generation FROM commerce_retention_jobs x
                WHERE x.current AND x.policy=(SELECT policy FROM requested) AND x.attempts<8
                  AND x.state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED','DUE_FOR_REVIEW','BLOCKED','REVIEW')
                  AND x.customer_id=h.subject_id AND x.customer_hold_revision<h.revision
                  AND (x.run_until IS NULL OR x.run_until<=statement_timestamp())
                ORDER BY x.customer_hold_revision,x.next_attempt_at,x.order_id LIMIT 16
              ) w WHERE h.scope_type='CUSTOMER' AND h.revision>0
            ) changed ON changed.order_id=j.order_id AND changed.generation=j.generation
            WHERE (j.run_until IS NULL OR j.run_until<=statement_timestamp())
            ORDER BY j.next_attempt_at,j.order_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
          ) customer_changed
          UNION ALL SELECT * FROM (
            SELECT j.order_id,j.generation,j.next_attempt_at FROM commerce_retention_jobs j
            JOIN (
              SELECT w.order_id,w.generation FROM commerce_retention_hold_scopes h
              CROSS JOIN LATERAL (
                SELECT x.order_id,x.generation FROM commerce_retention_jobs x
                WHERE x.order_id=h.subject_id AND x.current AND x.policy=(SELECT policy FROM requested) AND x.attempts<8
                  AND x.state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED','DUE_FOR_REVIEW','BLOCKED','REVIEW')
                  AND x.order_hold_revision<h.revision
                  AND (x.run_until IS NULL OR x.run_until<=statement_timestamp())
                OFFSET 0
              ) w WHERE h.scope_type='ORDER' AND h.revision>0
            ) changed ON changed.order_id=j.order_id AND changed.generation=j.generation
            WHERE (j.run_until IS NULL OR j.run_until<=statement_timestamp())
            ORDER BY j.next_attempt_at,j.order_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
          ) order_changed
          UNION ALL SELECT * FROM (
            SELECT j.order_id,j.generation,j.next_attempt_at FROM commerce_retention_jobs j
            WHERE j.current AND j.policy=(SELECT policy FROM requested) AND j.attempts<8
              AND j.state IN ('WAITING_PROOF','WAITING_AGE','FAILED','LEASED','DUE_FOR_REVIEW','BLOCKED','REVIEW')
              AND (j.order_hold_revision<0 OR j.customer_hold_revision<0)
              AND (j.run_until IS NULL OR j.run_until<=statement_timestamp())
            ORDER BY j.next_attempt_at,j.order_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
          ) legacy
        )
        SELECT j.* FROM commerce_retention_jobs j
        JOIN candidates c ON c.order_id=j.order_id AND c.generation=j.generation
        WHERE (j.run_until IS NULL OR j.run_until<=statement_timestamp())
        ORDER BY c.next_attempt_at,c.order_id LIMIT 1
        """;

    private static Object[] claimArguments(java.util.List<Object> scope){var args=new ArrayList<Object>();args.add(POLICY);args.addAll(scope);return args.toArray();}

    /** Persists only an assessment, under order-first locks and a fresh snapshot. */
    public Optional<Result> assess(Work work) {
        Objects.requireNonNull(work);
        return tx.execute(status -> {
            var owner = order(work.subject().orderId());
            var rows = jdbc.queryForList("SELECT * FROM commerce_retention_jobs WHERE order_id=? AND generation=? FOR UPDATE",
                    work.subject().orderId(), work.subject().chainGeneration());
            Instant now = jdbc.queryForObject("SELECT clock_timestamp()", (rs, n) -> rs.getTimestamp(1).toInstant());
            if (rows.isEmpty()) return Optional.empty();
            var row = rows.getFirst();
            if (!owns(row, work, now)) return Optional.empty();
            if (!sameOrder(row, owner)) {
                retire(work.subject().orderId(), work.subject().chainGeneration(), "ORDER_CHANGED");
                return Optional.empty();
            }
            var chain = new ArrayList<ChainProof>();
            var guards = new EnumMap<Guard, GuardProof>(Guard.class);
            for (var proof : jdbc.queryForList("SELECT * FROM commerce_retention_evidence WHERE order_id=? AND generation=? AND phase=? ORDER BY kind,source",
                    work.subject().orderId(), work.subject().chainGeneration(), work.phase().name())) {
                State state = State.valueOf((String) proof.get("state")); String policy = (String) proof.get("policy");
                if ("CHAIN".equals(proof.get("kind"))) {
                    chain.add(new ChainProof(Participant.valueOf((String) proof.get("source")), work.subject(), work.phase(), policy,
                            state, instant(proof, "reconciled_at"), instant(proof, "redacted_at")));
                } else {
                    var guard=Guard.valueOf((String)proof.get("source"));
                    if(guard==Guard.FIELD_SCOPE_REVIEWED && state==State.CONFIRMED){
                        // Historical unbound or changed definitions never become a
                        // trusted confirmation merely because their state says so.
                        try{if(!CommerceRetentionFieldScope.verifiedDigest().equals(proof.get("field_scope_hash")))state=State.UNKNOWN;}
                        catch(IllegalStateException unavailable){state=State.UNKNOWN;}
                    }else if(guard==Guard.PRESERVATION_POLICY_REVIEWED || guard==Guard.INDEPENDENT_JOURNAL_CURRENT){
                        // Historical confirmations predate complete policy/head evidence.
                        // Preserve blockers; a signed inclusion receipt is not freshness.
                        if(state==State.CONFIRMED)state=State.UNKNOWN;
                    }
                    guards.put(guard,new GuardProof(work.subject(),work.phase(),policy,state));
                }
            }
            var holdSnapshot=holds.snapshot(work.subject().orderId(),customer(owner),work.phase());
            Result result = holdSnapshot.quarantined() || !holdSnapshot.blockers().isEmpty()
                ? new Result(Outcome.BLOCKED,Code.PRESERVATION_OR_DEPENDENCY_BLOCK,null)
                : CommerceRetentionAssessment.assess(new Evidence(work.subject(), work.phase(), chain, guards), now);
            String state = switch (result.outcome()) {
                case DUE_FOR_REVIEW -> "DUE_FOR_REVIEW";
                case BLOCKED -> "BLOCKED";
                case REVIEW_REQUIRED -> "REVIEW";
                case WAIT -> result.code() == Code.AGE_NOT_REACHED ? "WAITING_AGE" : "WAITING_PROOF";
            };
            Instant retryAt = now.plusSeconds(30);
            if (result.code() == Code.AGE_NOT_REACHED) {
                retryAt = result.eligibleAt().isBefore(now.plusSeconds(86400)) ? result.eligibleAt() : now.plusSeconds(86400);
            }
            int saved = jdbc.update("""
                    UPDATE commerce_retention_jobs SET state=?,code=?,eligible_at=?,attempts=0,run_token=NULL,run_until=NULL,
                    next_attempt_at=?,order_hold_revision=?,customer_hold_revision=?,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND generation=? AND current
                    AND state='LEASED' AND policy=? AND phase=? AND run_token=? AND proof_revision=? AND run_until>clock_timestamp()
                    """, state, result.code().name(), timestamp(result.eligibleAt()), timestamp(retryAt),holdSnapshot.orderRevision(),holdSnapshot.customerRevision(),work.subject().orderId(),
                    work.subject().chainGeneration(), POLICY, work.phase().name(), work.token(), work.proofRevision());
            return saved == 1 ? Optional.of(result) : Optional.empty();
        });
    }

    /** A failed transaction is reported separately; expected waits reset the budget. */
    public boolean failed(Work work) {
        Objects.requireNonNull(work);
        return Boolean.TRUE.equals(tx.execute(status -> {
            var owner = order(work.subject().orderId());
            var rows = jdbc.queryForList("SELECT * FROM commerce_retention_jobs WHERE order_id=? AND generation=? FOR UPDATE",
                    work.subject().orderId(), work.subject().chainGeneration());
            Instant now = jdbc.queryForObject("SELECT clock_timestamp()", (rs, n) -> rs.getTimestamp(1).toInstant());
            if (rows.isEmpty() || !owns(rows.getFirst(), work, now)) return false;
            if (!sameOrder(rows.getFirst(), owner)) {
                retire(work.subject().orderId(), work.subject().chainGeneration(), "ORDER_CHANGED");
                return false;
            }
            return jdbc.update("""
                    UPDATE commerce_retention_jobs SET state='FAILED',code=CASE WHEN attempts=8 THEN 'RETRY_LIMIT' ELSE 'ASSESSMENT_FAILED' END,
                    run_token=NULL,run_until=NULL,next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds',updated_at=CURRENT_TIMESTAMP
                    WHERE order_id=? AND generation=? AND current AND policy=? AND phase=? AND state='LEASED'
                    AND run_token=? AND run_until>clock_timestamp() AND proof_revision=?
                    """, work.subject().orderId(), work.subject().chainGeneration(), POLICY, work.phase().name(), work.token(), work.proofRevision()) == 1;
        }));
    }

    private Map<String, Object> order(long id) {
        return jdbc.queryForMap("SELECT id,customer_id,version FROM orders WHERE id=? FOR NO KEY UPDATE", id);
    }

    private int retire(long order, UUID generation, String code) {
        return jdbc.update("UPDATE commerce_retention_jobs SET current=FALSE,state='STALE',code=?,run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND generation=? AND current", code, order, generation);
    }

    private static boolean owns(Map<String, Object> row, Work work, Instant now) {
        return Boolean.TRUE.equals(row.get("current")) && "LEASED".equals(row.get("state"))
                && POLICY.equals(row.get("policy")) && work.phase().name().equals(row.get("phase"))
                && subject(row).equals(work.subject()) && work.token().equals(row.get("run_token"))
                && instant(row, "run_until").isAfter(now) && number(row, "proof_revision") == work.proofRevision();
    }

    private static boolean sameOrder(Map<String, Object> job, Map<String, Object> order) {
        return number(job, "order_id") == number(order, "id") && customer(job) == customer(order)
                && number(job, "order_version") == number(order, "version");
    }

    private static Subject subject(Map<String, Object> row) {
        return new Subject(number(row, "order_id"), customer(row), number(row, "order_version"), (UUID) row.get("generation"));
    }
    private static long customer(Map<String, Object> row) { return row.get("customer_id") == null ? 0 : number(row, "customer_id"); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static Instant instant(Map<String, Object> row, String key) { return row.get(key) == null ? null : ((Timestamp) row.get(key)).toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static void requireDatabasePrecision(Instant value) {
        if (value != null && value.getNano() % 1000 != 0) throw new IllegalArgumentException("Retention clock requires database precision");
    }
}
