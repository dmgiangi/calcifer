package tech.calcifer.ragequit;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

import static tech.calcifer.ragequit.TrackingDtos.*;

@RestController
@RequestMapping("/api")
public class TrackingController {
    private final EventStore store;
    private final RageQuitProperties properties;

    public TrackingController(EventStore store, RageQuitProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @GetMapping("/session")
    public ResponseEntity<Session> session(@AuthenticationPrincipal OidcUser user, CsrfToken csrf) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Session(
                Participants.requireIdentity(user).alias(), properties.startDate(), store.today(), csrf.getToken(), csrf.getHeaderName()));
    }

    @GetMapping("/events")
    public History history(@AuthenticationPrincipal OidcUser user) { return store.history(owner(user)); }

    @GetMapping("/events/{id}")
    public Event event(@AuthenticationPrincipal OidcUser user, @PathVariable String id) { return store.get(owner(user), id); }

    @PostMapping("/events")
    public Event create(@AuthenticationPrincipal OidcUser user, @Valid @RequestBody CreateEvent input) {
        return store.create(owner(user), input);
    }

    @PatchMapping("/events/{id}")
    public Event update(@AuthenticationPrincipal OidcUser user, @PathVariable String id, @Valid @RequestBody UpdateEvent input) {
        return store.update(owner(user), id, input);
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal OidcUser user, @PathVariable String id, @RequestParam long version) {
        store.delete(owner(user), id, version);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/zero/{date}")
    public ZeroDeclaration confirm(@AuthenticationPrincipal OidcUser user, @PathVariable LocalDate date) {
        return store.confirmZero(owner(user), date);
    }

    @DeleteMapping("/zero/{date}")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal OidcUser user, @PathVariable LocalDate date) {
        store.withdrawZero(owner(user), date);
        return ResponseEntity.noContent().build();
    }

    private static String owner(OidcUser user) { return Participants.requireIdentity(user).subject(); }
}
