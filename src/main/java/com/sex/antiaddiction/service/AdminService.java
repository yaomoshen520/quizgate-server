package com.sex.antiaddiction.service;

import com.quizgate.proto.AccountSummary;
import com.quizgate.proto.CreateInviteReply;
import com.quizgate.proto.CreateInviteRequest;
import com.quizgate.proto.InviteSummary;
import com.quizgate.proto.ListAccountsReply;
import com.quizgate.proto.ListInvitesReply;
import com.quizgate.proto.ResetPasswordReply;
import com.quizgate.proto.ResetPasswordRequest;
import com.sex.antiaddiction.domain.Rows.Invite;
import com.sex.antiaddiction.infra.ProtobufHttp.BadProtoRequest;
import com.sex.antiaddiction.repo.AccountStore;
import com.sex.antiaddiction.service.ParentService.Actor;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 平台管理员视角。这里刻意不做"家长也能进来"的分支——能不能进由安全链决定，
 * 服务层假定调用者已经是 ADMIN，只负责把全量数据如实端出来。
 */
@Service
public class AdminService {

    private final AccountStore accounts;
    private final PasswordEncoder passwords;

    public AdminService(AccountStore accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public ListAccountsReply listAccounts() {
        var builder = ListAccountsReply.newBuilder();
        for (var account : accounts.allAccounts()) {
            builder.addAccounts(AccountSummary.newBuilder()
                    .setAccountId(Long.toString(account.id()))
                    .setUsername(account.username())
                    .setDisplayName(account.displayName())
                    .setRole(account.role())
                    .setChildCount(account.childCount())
                    .build());
        }
        return builder.build();
    }

    public CreateInviteReply createInvite(Actor actor) {
        return CreateInviteReply.newBuilder()
                .setCode(accounts.createInvite(actor.accountId())).build();
    }

    public ListInvitesReply listInvites() {
        var builder = ListInvitesReply.newBuilder();
        for (Invite invite : accounts.allInvites()) {
            var item = InviteSummary.newBuilder()
                    .setCode(invite.code())
                    .setCreatedByAccountId(Long.toString(invite.createdByAccountId()))
                    .setCreatedAtMs(invite.createdAtMs());
            // 没用过的码留空串，而不是回 "0"——0 会被当成一个真实的账号 id。
            if (invite.usedByAccountId() != 0L) {
                item.setUsedByAccountId(Long.toString(invite.usedByAccountId()));
            }
            builder.addInvites(item.build());
        }
        return builder.build();
    }

    /**
     * 找回密码走的就是这一条：没有邮件/短信通道，所以重置口令是管理员的活。
     * 重置之后必须吊销那口子的人用令牌，否则"改完口令把攻击者踢下线"这个目的达不到。
     */
    public ResetPasswordReply resetPassword(ResetPasswordRequest request) {
        long accountId;
        try {
            accountId = Long.parseLong(request.getAccountId().trim());
        } catch (RuntimeException e) {
            throw new BadProtoRequest("account_id 不是数字");
        }
        var account = accounts.account(accountId).orElseThrow(() -> new BadProtoRequest("账号不存在"));
        int bytes = request.getNewPassword().getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 8 || bytes > 72) {
            throw new BadProtoRequest("新口令要 8~72 字节");
        }
        accounts.updatePassword(account.id(), passwords.encode(request.getNewPassword()));
        accounts.revokeHumanTokens(account.id());
        return ResetPasswordReply.newBuilder()
                .setMessage("已重置 " + account.username() + " 的口令，该账号此前的登录令牌全部失效").build();
    }
}
