package org.demo.paymentservice.web;

import org.demo.paymentservice.followup.*;
import org.demo.platformsecurity.Access;
import org.demo.platformsecurity.ListWindow;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;

@RestController
@RequestMapping("/payments/follow-ups")
public class FollowUpController {
    private final FollowUpStateStore states;
    public FollowUpController(FollowUpStateStore states) { this.states=states; }
    @GetMapping public ResponseEntity<List<FollowUpResponse>> list(
            @RequestParam(defaultValue="0") long cursor,@RequestParam(defaultValue="50") int size,
            @RequestParam(required=false) FollowUpStatus status,@RequestParam(required=false) Long orderId) {
        Access.require(Access.actor().operator());
        return states.list(status,orderId,new ListWindow(cursor,size)).response();
    }
    @PostMapping("/{id}/replay") public FollowUpResponse replay(@PathVariable Long id) {
        Access.require(Access.actor().operator());return states.replay(id);
    }
}
