package com.sex.antiaddiction.web;
import com.quizgate.proto.*;
import com.sex.antiaddiction.repo.AccountStore;
import com.sex.antiaddiction.repo.GameStore;
import com.sex.antiaddiction.security.AppPrincipal;
import com.sex.antiaddiction.service.ParentService.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.sex.antiaddiction.infra.ProtobufHttp.*;

@RestController
@RequestMapping(path="/api",consumes=PROTOBUF_VALUE,produces=PROTOBUF_VALUE)
public class GameApi {
    private final GameStore games;
    private final AccountStore accounts;
    private final com.sex.antiaddiction.repo.GameApprovalStore approvals;
    public GameApi(GameStore games,AccountStore accounts,com.sex.antiaddiction.repo.GameApprovalStore approvals) {
        this.games=games;this.accounts=accounts;this.approvals=approvals;
    }
    @PostMapping("/device/app-inventory")
    public ResponseEntity<byte[]> inventory(@AuthenticationPrincipal AppPrincipal actor,@RequestBody(required=false) byte[] body) {
        return reply(games.report(actor.subjectId(),parse(body,AppInventoryRequest.parser())));
    }
    @PostMapping("/parent/list-apps")
    public ResponseEntity<byte[]> list(@AuthenticationPrincipal AppPrincipal actor,@RequestBody(required=false) byte[] body) {
        return reply(games.list(Actor.of(actor,accounts),parse(body,ListAppsRequest.parser())));
    }
    @PostMapping("/parent/classify-app")
    public ResponseEntity<byte[]> classify(@AuthenticationPrincipal AppPrincipal actor,@RequestBody(required=false) byte[] body) {
        return reply(games.classify(Actor.of(actor,accounts),parse(body,ClassifyAppRequest.parser())));
    }
    @PostMapping("/device/game-extension")
    public ResponseEntity<byte[]> apply(@AuthenticationPrincipal AppPrincipal actor,@RequestBody(required=false) byte[] body) {
        return reply(approvals.apply(actor.subjectId(),parse(body,ApplyGameExtensionRequest.parser())));
    }
    @PostMapping("/device/game-extension-status")
    public ResponseEntity<byte[]> extensionStatus(@AuthenticationPrincipal AppPrincipal actor,@RequestBody(required=false) byte[] body) {
        return reply(approvals.status(actor.subjectId(),parse(body,GameExtensionStatusRequest.parser())));
    }
    @PostMapping("/parent/list-game-extensions")
    public ResponseEntity<byte[]> extensions(@AuthenticationPrincipal AppPrincipal actor,@RequestBody(required=false) byte[] body) {
        return reply(approvals.list(Actor.of(actor,accounts),parse(body,ListGameExtensionsRequest.parser())));
    }
    @PostMapping("/parent/decide-game-extension")
    public ResponseEntity<byte[]> decide(@AuthenticationPrincipal AppPrincipal actor,@RequestBody(required=false) byte[] body) {
        return reply(approvals.decide(Actor.of(actor,accounts),parse(body,DecideGameExtensionRequest.parser())));
    }
}
