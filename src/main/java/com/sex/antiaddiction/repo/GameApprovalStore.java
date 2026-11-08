package com.sex.antiaddiction.repo;

import com.quizgate.proto.*;
import com.sex.antiaddiction.domain.Rows.Device;
import com.sex.antiaddiction.domain.Rows.Rule;
import com.sex.antiaddiction.infra.ProtobufHttp.BadProtoRequest;
import com.sex.antiaddiction.service.ChildScope;
import com.sex.antiaddiction.service.ParentService.Actor;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.quizgate.proto.GameExtensionState.*;

/** One parent application per device round. Neither retries nor repeated decisions create a grant. */
@Service
public class GameApprovalStore {
    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final ChildScope scope;
    public GameApprovalStore(JdbcTemplate jdbc, AccountStore accounts, ChildScope scope) {
        this.jdbc=jdbc; this.accounts=accounts; this.scope=scope;
    }
    private static void cycle(String value) {
        try { if(value.length()!=36) throw new IllegalArgumentException(); UUID.fromString(value); }
        catch(RuntimeException error) { throw new BadProtoRequest("使用周期编号无效"); }
    }
    private Device lockDevice(long id) {
        if(jdbc.queryForList("select id from device where id=? for update",id).isEmpty())
            throw new BadProtoRequest("设备不存在");
        return accounts.device(id).orElseThrow();
    }
    @Transactional
    public void observeCycle(long device, String value) {
        if(!value.isEmpty()) cycle(value);
        var current=lockDevice(device);
        closeStale(current,value);
        jdbc.update("update device set game_cycle_id=? where id=?",value,device);
    }
    private void closeStale(Device device,String currentCycle) {
        jdbc.update("update game_extension_request set state=5 where device_id=? and (child_id<>? or (cycle_id<>? and state in (1,2)))",
            device.id(),device.childId(),currentCycle);
    }
    @Transactional
    public void rememberPolicy(Device device, Rule rule, HeartbeatRequest heartbeat) {
        if(device.childId()==0) return;
        lockDevice(device.id());
        jdbc.update("merge into issued_game_policy (device_id,rule_version,child_id,enabled,extension_minutes,max_extensions,game_packages) key(device_id,rule_version) values (?,?,?,?,?,?,?)",
            device.id(),rule.version(),device.childId(),rule.enabled() && rule.gameMode().getEnabled(),
            rule.gameMode().getExtensionMinutes(),rule.gameMode().getMaxExtensions(),String.join("\n",rule.gameMode().getGamePackagesList()));
        // At most three policies per device: newly issued, currently applied and cached/known.
        jdbc.update("delete from issued_game_policy where device_id=? and rule_version<>? and rule_version<>? and rule_version<>?",
            device.id(),rule.version(),heartbeat.getAppliedRuleVersion(),heartbeat.getKnownRuleVersion());
    }
    private GameExtension find(long device, String cycle) {
        var rows=jdbc.query("select r.*,d.device_model from game_extension_request r join device d on d.id=r.device_id where r.device_id=? and r.cycle_id=?",
            (rs,n)->toProto(rs),device,cycle);
        return rows.isEmpty()?GameExtension.getDefaultInstance():rows.getFirst();
    }
    private static GameExtension toProto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return GameExtension.newBuilder().setRequestId(rs.getString("request_id"))
                .setDeviceId(Long.toString(rs.getLong("device_id"))).setCycleId(rs.getString("cycle_id"))
                .setPackageName(rs.getString("package_name")).setExtensionMinutes(rs.getInt("extension_minutes"))
                .setRuleVersion(rs.getLong("rule_version")).setStateValue(rs.getInt("state"))
                .setRequestedAtMs(rs.getLong("requested_at_ms")).setDecidedAtMs(rs.getLong("decided_at_ms"))
                .setDeviceModel(rs.getString("device_model")==null?"":rs.getString("device_model")).build();
    }
    private String currentCycle(long device) {
        return jdbc.queryForObject("select coalesce(game_cycle_id,'') from device where id=?",String.class,device);
    }
    @Transactional
    public GameExtension apply(long deviceId, ApplyGameExtensionRequest request) {
        cycle(request.getCycleId());
        var device=lockDevice(deviceId);
        if(device.childId()==0 || !request.getCycleId().equals(currentCycle(deviceId)))
            throw new BadProtoRequest("请先同步当前使用周期");
        closeStale(device,request.getCycleId());
        var existing=find(deviceId,request.getCycleId());
        if(!existing.getRequestId().isEmpty()) return existing;
        long version=jdbc.queryForObject("select applied_rule_version from device where id=?",Long.class,deviceId);
        var policies=jdbc.query("select * from issued_game_policy where device_id=? and rule_version=? and child_id=?",
            (rs,n)->GameModeRule.newBuilder().setEnabled(rs.getBoolean("enabled"))
                .setExtensionMinutes(rs.getInt("extension_minutes")).setMaxExtensions(rs.getInt("max_extensions"))
                .addAllGamePackages(rs.getString("game_packages").lines().toList()).build(),deviceId,version,device.childId());
        if(policies.isEmpty()) throw new BadProtoRequest("本轮规则尚未在服务端确认，请先同步家长规则");
        var policy=policies.getFirst();
        int used=jdbc.queryForObject("select extensions_used from device where id=?",Integer.class,deviceId);
        if(!policy.getEnabled() || policy.getExtensionMinutes()<1 || policy.getExtensionMinutes()>15 ||
            !policy.getGamePackagesList().contains(request.getPackageName()) || used<policy.getMaxExtensions())
            throw new BadProtoRequest("仅本轮受管控游戏用完自主延期后可申请一次");
        String id=UUID.randomUUID().toString();
        jdbc.update("insert into game_extension_request (request_id,device_id,child_id,cycle_id,package_name,extension_minutes,rule_version,state,requested_at_ms) values (?,?,?,?,?,?,?,?,?)",
            id,deviceId,device.childId(),request.getCycleId(),request.getPackageName(),policy.getExtensionMinutes(),version,1,System.currentTimeMillis());
        return find(deviceId,request.getCycleId());
    }
    @Transactional
    public GameExtension status(long deviceId, GameExtensionStatusRequest request) {
        cycle(request.getCycleId());
        var device=lockDevice(deviceId);
        closeStale(device,currentCycle(deviceId));
        var result=find(deviceId,request.getCycleId());
        if(result.getRequestId().isEmpty()) return result;
        if(!request.getCycleId().equals(currentCycle(deviceId)) || request.getCancel()) {
            jdbc.update("update game_extension_request set state=5 where request_id=? and state in (1,2)",result.getRequestId());
        } else if(!request.getAppliedRequestId().isEmpty()) {
            if(!request.getAppliedRequestId().equals(result.getRequestId())) throw new BadProtoRequest("审批确认编号不匹配");
            jdbc.update("update game_extension_request set state=4 where request_id=? and state=2",result.getRequestId());
        }
        return find(deviceId,request.getCycleId());
    }
    public ListGameExtensionsReply list(Actor actor, ListGameExtensionsRequest request) {
        long child=scope.require(actor,request.getChildId()).id();
        var rows=jdbc.query("select r.*,d.device_model from game_extension_request r join device d on d.id=r.device_id where r.child_id=? order by r.requested_at_ms desc limit 50",
            (rs,n)->toProto(rs),child);
        return ListGameExtensionsReply.newBuilder().addAllRequests(rows).build();
    }
    @Transactional
    public GameExtension decide(Actor actor, DecideGameExtensionRequest request) {
        record Target(long device, long child, String cycle) {}
        var rows=jdbc.query("select device_id,child_id,cycle_id from game_extension_request where request_id=?",
            (rs,n)->new Target(rs.getLong("device_id"),rs.getLong("child_id"),rs.getString("cycle_id")),request.getRequestId());
        if(rows.isEmpty()) throw new BadProtoRequest("申请不存在");
        var row=rows.getFirst();
        long child=row.child();
        scope.require(actor,Long.toString(child));
        long deviceId=row.device();
        String cycleId=row.cycle();
        var device=lockDevice(deviceId);
        closeStale(device,currentCycle(deviceId));
        if(device.childId()!=child || !cycleId.equals(currentCycle(deviceId))) {
            jdbc.update("update game_extension_request set state=5 where request_id=? and state in (1,2)",request.getRequestId());
            return find(deviceId,cycleId);
        }
        var existing=find(deviceId,cycleId);
        var desired=request.getApprove()?GAME_EXTENSION_APPROVED:GAME_EXTENSION_REJECTED;
        if(existing.getState()==desired || (request.getApprove() && existing.getState()==GAME_EXTENSION_APPLIED)) return existing;
        if(existing.getState()!=GAME_EXTENSION_PENDING) throw new BadProtoRequest("申请已处理或已结束，请刷新");
        jdbc.update("update game_extension_request set state=?,decided_at_ms=?,decided_by=? where request_id=? and state=1",
            desired.getNumber(),System.currentTimeMillis(),actor.accountId(),request.getRequestId());
        return find(deviceId,cycleId);
    }
}
