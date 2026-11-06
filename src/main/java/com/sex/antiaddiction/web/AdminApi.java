package com.sex.antiaddiction.web;

import com.quizgate.proto.CreateInviteRequest;
import com.quizgate.proto.ListAccountsRequest;
import com.quizgate.proto.ListInvitesRequest;
import com.quizgate.proto.ResetPasswordRequest;
import com.sex.antiaddiction.repo.AccountStore;
import com.sex.antiaddiction.security.AppPrincipal;
import com.sex.antiaddiction.service.AdminService;
import com.sex.antiaddiction.service.ParentService.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.sex.antiaddiction.infra.ProtobufHttp.PROTOBUF_VALUE;
import static com.sex.antiaddiction.infra.ProtobufHttp.parse;
import static com.sex.antiaddiction.infra.ProtobufHttp.reply;

/** 只有管理员令牌进得来，这条档位在安全链上单独挂着 hasRole("ADMIN")。 */
@RestController
@RequestMapping(path = "/api/admin", consumes = PROTOBUF_VALUE, produces = PROTOBUF_VALUE)
public class AdminApi {

    private final AdminService admin;
    private final AccountStore accounts;

    public AdminApi(AdminService admin, AccountStore accounts) {
        this.admin = admin;
        this.accounts = accounts;
    }

    @PostMapping("/list-accounts")
    public ResponseEntity<byte[]> listAccounts(@RequestBody(required = false) byte[] body) {
        parse(body, ListAccountsRequest.parser());
        return reply(admin.listAccounts());
    }

    @PostMapping("/create-invite")
    public ResponseEntity<byte[]> createInvite(@AuthenticationPrincipal AppPrincipal principal,
            @RequestBody(required = false) byte[] body) {
        parse(body, CreateInviteRequest.parser());
        return reply(admin.createInvite(Actor.of(principal, accounts)));
    }

    @PostMapping("/list-invites")
    public ResponseEntity<byte[]> listInvites(@RequestBody(required = false) byte[] body) {
        parse(body, ListInvitesRequest.parser());
        return reply(admin.listInvites());
    }

    @PostMapping("/reset-password")
    public ResponseEntity<byte[]> resetPassword(@RequestBody(required = false) byte[] body) {
        return reply(admin.resetPassword(parse(body, ResetPasswordRequest.parser())));
    }
}
