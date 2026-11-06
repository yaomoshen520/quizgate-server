package com.sex.antiaddiction.service;

import com.sex.antiaddiction.domain.Rows.Child;
import com.sex.antiaddiction.infra.ProtobufHttp.BadProtoRequest;
import com.sex.antiaddiction.repo.AccountStore;
import org.springframework.stereotype.Service;

/**
 * "这个调用者能不能碰这个孩子"的唯一一份答案。
 * <p>
 * 单独立一个类是因为核对/删除那两个入口的 child_id 不在请求里，只能从题号反查归属再问一次。
 * 那种"顺手再写一小段判断"的抄法正是跨户漏口的来源：两处判断迟早有一处忘了 ADMIN 那一支。
 */
@Service
public class ChildScope {

    private final AccountStore accounts;

    public ChildScope(AccountStore accounts) {
        this.accounts = accounts;
    }

    /**
     * 越权和不存在回同一句话：都不透露别人家的 id。
     * ADMIN 可以跨孩子，但那是库里 role 列说了算，不是令牌 kind。
     */
    public Child require(ParentService.Actor actor, String childId) {
        long id;
        try {
            id = Long.parseLong(childId.trim());
        } catch (RuntimeException e) {
            throw new BadProtoRequest("child_id 不是数字");
        }
        var child = accounts.child(id).orElse(null);
        if (child == null) {
            throw new BadProtoRequest("child 不存在");
        }
        if (!actor.isAdmin() && child.parentId() != actor.accountId()) {
            throw new BadProtoRequest("child 不存在");
        }
        return child;
    }
}
