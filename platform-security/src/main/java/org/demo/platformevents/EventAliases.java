package org.demo.platformevents;

import org.springframework.jdbc.core.JdbcTemplate;

/** Must run in the SAME local transaction as the inbox receipt and business effect. */
public final class EventAliases {
    private EventAliases() {}
    public static void bind(JdbcTemplate jdbc,EventIdentity.Envelope event){
        if(event.aliasId()==null)return;
        if(jdbc==null)throw new IllegalStateException("Transactional event aliases are unavailable");
        jdbc.update("INSERT INTO event_identity_aliases(alias_id,canonical_id) VALUES(?,?) ON CONFLICT(alias_id) DO NOTHING",event.aliasId(),event.canonicalId());
        String previous=jdbc.queryForObject("SELECT canonical_id FROM event_identity_aliases WHERE alias_id=? FOR UPDATE",String.class,event.aliasId());
        if(!event.canonicalId().equals(previous))throw new IllegalArgumentException("Event alias was reused with different content");
    }
}
