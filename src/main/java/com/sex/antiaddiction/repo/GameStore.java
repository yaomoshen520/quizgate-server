package com.sex.antiaddiction.repo;

import com.quizgate.proto.*;
import com.sex.antiaddiction.infra.ProtobufHttp.BadProtoRequest;
import com.sex.antiaddiction.service.ChildScope;
import com.sex.antiaddiction.service.ParentService.Actor;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameStore {
    private final JdbcTemplate jdbc;
    private final AccountStore accounts;
    private final ChildScope scope;
    private final GameApprovalStore approvals;
    public GameStore(JdbcTemplate jdbc, AccountStore accounts, ChildScope scope, GameApprovalStore approvals) {
        this.jdbc=jdbc; this.accounts=accounts; this.scope=scope; this.approvals=approvals;
    }
    public static boolean isGame(String pkg, boolean systemGame, int override) {
        if ("com.quizgate.spike".equals(pkg)) return false;
        if (override != 0) return override == 1;
        return systemGame || Set.of("com.tencent.tmgp.sgame", "com.tencent.tmgp.pubgmhd").contains(pkg);
    }
    private static void requirePackage(String pkg) {
        if (pkg.length()>128 || !pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*"))
            throw new BadProtoRequest("应用包名格式不正确");
    }
    @Transactional
    public AppInventoryReply report(long deviceId, AppInventoryRequest request) {
        var device=accounts.device(deviceId).orElseThrow(()->new BadProtoRequest("设备不存在"));
        if(device.childId()==0) throw new BadProtoRequest("请先绑定孩子，再同步应用列表");
        if(request.getAppsCount()>2000 || request.getCoverage().length()>64)
            throw new BadProtoRequest("应用清单过大");
        var seen=new HashSet<String>();
        for(var app:request.getAppsList()) {
            requirePackage(app.getPackageName());
            if(app.getLabel().length()>160 || !seen.add(app.getPackageName()))
                throw new BadProtoRequest("应用名称过长或清单包含重复包名");
        }
        long now=System.currentTimeMillis();
        if(request.getConsent()) {
            jdbc.update("delete from device_app where device_id=?", deviceId);
            for(var app:request.getAppsList())
                jdbc.update("insert into device_app (device_id,package_name,label,system_game) values (?,?,?,?)",
                    deviceId,app.getPackageName(),app.getLabel(),app.getSystemGame());
        }
        jdbc.update("merge into device_app_snapshot (device_id,consent,coverage,synced_at_ms) key(device_id) values (?,?,?,?)",
            deviceId,request.getConsent(),request.getCoverage(),now);
        accounts.bumpRuleVersion(device.childId());
        return AppInventoryReply.newBuilder().setSyncedAtMs(now).build();
    }
    @Transactional
    public void observe(long deviceId, HeartbeatRequest request) {
        String pkg=request.getForegroundPackage();
        if(!pkg.isEmpty()) requirePackage(pkg);
        if(request.getExtensionsUsed()<0 || request.getExtensionsUsed()>2 || request.getGameState().length()>64)
            throw new BadProtoRequest("游戏状态不正确");
        approvals.observeCycle(deviceId,request.getGameCycleId());
        jdbc.update("update device set foreground_package=?,observed_at_ms=?,applied_rule_version=?,extensions_used=?,game_state=? where id=?",
            pkg,System.currentTimeMillis(),request.getAppliedRuleVersion(),request.getExtensionsUsed(),request.getGameState(),deviceId);
    }
    public ListAppsReply list(Actor actor,ListAppsRequest request) {
        long child=scope.require(actor,request.getChildId()).id();
        var reply=ListAppsReply.newBuilder();
        jdbc.query("select d.*,s.consent,s.coverage,s.synced_at_ms from device d left join device_app_snapshot s on s.device_id=d.id"
            + " where d.child_id=? order by d.id", (org.springframework.jdbc.core.RowCallbackHandler) rs->{
            long id=rs.getLong("id");
            var device=DeviceApps.newBuilder().setDeviceId(Long.toString(id)).setDeviceModel(text(rs.getString("device_model")))
                .setConsent(rs.getBoolean("consent")).setCoverage(text(rs.getString("coverage")))
                .setSyncedAtMs(rs.getLong("synced_at_ms")).setForegroundPackage(text(rs.getString("foreground_package")))
                .setObservedAtMs(rs.getLong("observed_at_ms")).setAppliedRuleVersion(rs.getLong("applied_rule_version"))
                .setExtensionsUsed(rs.getInt("extensions_used")).setGameState(text(rs.getString("game_state")));
            if(device.getConsent()) device.addAllApps(jdbc.query("select a.*,coalesce(o.classification,0) as classification from device_app a"
                + " left join game_classification o on o.child_id=? and o.package_name=a.package_name where a.device_id=? order by a.label,a.package_name",
                (a,n)->InstalledApp.newBuilder().setPackageName(a.getString("package_name")).setLabel(a.getString("label"))
                    .setSystemGame(a.getBoolean("system_game")).setClassification(a.getInt("classification"))
                    .setIsGame(isGame(a.getString("package_name"),a.getBoolean("system_game"),a.getInt("classification"))).build(),child,id));
            reply.addDevices(device);
        },child);
        return reply.build();
    }
    @Transactional
    public ListAppsReply classify(Actor actor,ClassifyAppRequest request) {
        long child=scope.require(actor,request.getChildId()).id();
        requirePackage(request.getPackageName());
        if(request.getClassification()<0 || request.getClassification()>2) throw new BadProtoRequest("分类不正确");
        jdbc.update("merge into game_classification (child_id,package_name,classification) key(child_id,package_name) values (?,?,?)",
            child,request.getPackageName(),request.getClassification());
        accounts.bumpRuleVersion(child);
        return list(actor,ListAppsRequest.newBuilder().setChildId(request.getChildId()).build());
    }
    private static String text(String value) { return value==null?"":value; }
}
