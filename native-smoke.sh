#!/usr/bin/env bash
# 原生镜像的验收脚本：JVM 测试测不到 native 才会炸的那一类问题（反射元数据、资源打包、驱动注册），
# 所以每次改依赖或改安全链之后，用 mvn -Pnative native:compile 重新出包，再跑这一遍。
# 出包前先在 web/ 里 npm run build：产物写进 src/main/resources/static，镜像烘焙的是打包那一刻的文件。
# 只改前端不重新出包，浏览器拿到的还是上一版页面而且一声不响——第 11 步逐个文件回查产物，专治这个。
#
#   1) cmd //c native-build.bat     # vcvars64 + mvnw -Pnative native:compile，产物 target/anti-addiction.exe
#   2) 用同一套环境变量启动那个 exe（口令走 export，别写进文件），然后 bash native-smoke.sh
set -euo pipefail
cd "$(dirname "$0")"

API=${API:-http://127.0.0.1:8080}
: "${QUIZGATE_PARENT_USERNAME:?先 export 家长账号，脚本里不存口令}"
: "${QUIZGATE_PARENT_PASSWORD:?先 export 家长口令，脚本里不存口令}"

PROTOC=$(find target/protobuf-maven-plugin -name 'protoc-*.exe' | head -1)
[ -n "$PROTOC" ] || { echo "找不到 protoc，先跑一次 mvnw compile 让它被拉下来"; exit 1; }

enc() { printf '%s' "$2" | "$PROTOC" -I proto --encode="quizgate.$1" proto/quizgate.proto > tmp-req.bin; }
post() { # post <path> <token|-> <label> <replyType|空>
  local hdr=() code
  [ "$2" != "-" ] && hdr=(-H "X-Quizgate-Token: $2")
  printf '%-26s' "$3"
  code=$(curl -s -X POST "$API/$1" "${hdr[@]}" -H 'Content-Type: application/x-protobuf' \
    --data-binary @tmp-req.bin -o tmp-resp.bin -w '%{http_code}')
  # 非 200 一律当场红掉。这套接口的契约就是 200 + protobuf；以前只把 http=403 打在屏幕上继续往下跑，
  # 结果一次被安全链伪装成 403 的服务端崩溃，冒烟脚本自己却是绿的——那比没有脚本更危险。
  if [ "$code" != 200 ]; then
    echo "http=$code 服务端答复：$(head -c 200 tmp-resp.bin)"
    exit 1
  fi
  printf 'http=200 '
  if [ -n "$4" ]; then
    if "$PROTOC" -I proto --decode="quizgate.$4" proto/quizgate.proto < tmp-resp.bin > tmp-out.txt 2>/dev/null; then
      head -c 400 tmp-out.txt | tr '\n' ' '; echo
    else
      echo " protobuf 解不开，原文："; head -c 200 tmp-resp.bin; echo
      exit 1
    fi
  else
    head -c 120 tmp-resp.bin; echo
  fi
}
field() { sed -n "s/^.*$1: \"\\(.*\\)\".*$/\\1/p" tmp-out.txt | head -1; }

enc LoginRequest "username:\"$QUIZGATE_PARENT_USERNAME\" password:\"$QUIZGATE_PARENT_PASSWORD\""
post api/parent/login - '1 家长登录' LoginReply
PT=$(field parent_token)
# 孩子的数据库主键不能写死成 1：空库第一次建号时它不一定是 1，
# 而写死 1 的脚本只会在一复现就红的路上越走越远——它一直在偷用上一轮遗留的那个库。
CHILD=$(field child_id)
[ -n "$CHILD" ] || { echo " 第 1 步没从登录应答里解出 child_id，后面几步没有归属可查"; exit 1; }

enc EnrollRequest "install_id:\"smoke-$(date +%s)\" device_model:\"Pixel4\" android_api_level:30"
post api/device/enroll - '2 设备登记' EnrollReply
DT=$(field device_token); CODE=$(field bind_code)

enc BindDeviceRequest "child_id:\"$CHILD\" bind_code:\"$CODE\""
post api/parent/bind-device "$PT" '3 家长认领设备' BindDeviceReply

# 3b：刚登记、还没绑定的设备不能取题/判分/报用量。
# 绑不上就先记账，用量会以 child_id=0 落库、且客户端已经把队列删了，绑定之后永远补不回来。
enc EnrollRequest "install_id:\"smoke-unbound-$(date +%s)\" device_model:\"Pixel4\" android_api_level:30"
post api/device/enroll - '3b0 第二台设备登记（不绑定）' EnrollReply
UT=$(field device_token)
for p in next-question usage; do
  case $p in
    next-question) enc NextQuestionRequest 'grade:2' ;;
    usage) enc UsageReportRequest 'events:{event_id:"smoke-unbound-1" occurred_at_ms:1762000000000 app_package:"com.video.app" played_ms:60000}' ;;
  esac
  code=$(curl --ssl-no-revoke -s -X POST "$API/api/device/$p" -H "X-Quizgate-Token: $UT" \
    -H 'Content-Type: application/x-protobuf' --data-binary @tmp-req.bin -o tmp-resp.bin -w '%{http_code}')
  [ "$code" = "400" ] || { echo "3b 未绑定设备 $p 拿到 http=$code，期望 400"; exit 1; }
  echo "3b 未绑定设备 $p 被拒（http=400）"
done

enc NextQuestionRequest 'grade:2'
post api/device/next-question "$DT" '4 闸门取题' NextQuestionReply
QID=$(field question_id)

enc AnswerRequest "question_id:\"$QID\" selected_index:0"
post api/device/answer "$DT" '5a 提交下标0（对错看抽到哪题）' AnswerReply
enc AnswerRequest "question_id:\"$QID\" selected_index:1"
post api/device/answer "$DT" '5b 同题重复提交（该判过期）' AnswerReply

enc UsageReportRequest 'events:{event_id:"smoke-1" occurred_at_ms:1762000000000 app_package:"com.video.app" played_ms:60000 correct_count:1 wrong_count:1}'
post api/device/usage "$DT" '6a 用量上报（首次）' UsageReportReply
post api/device/usage "$DT" '6b 同 event_id 重报' UsageReportReply

enc HeartbeatRequest 'accessibility_connected:true accumulated_ms:120000 known_rule_version:0'
post api/device/heartbeat "$DT" '7 心跳拉规则' HeartbeatReply
# 7b：归属这件事要能随心跳回到设备上。设备只在 enroll 那一次见过绑定码，
# 之后没人通知它，首页就会一辈子挂着"去后台抄这六个字符"。
# 反向那半只看"不许出现 true"：proto3 的文本格式不打印取默认值的字段，
# 未绑定的那台在这里根本不会出现 device_bound 这一行，硬要求它等于 false 是假断言。
grep -q '^device_bound: true' tmp-out.txt || { echo "7b 已认领设备的心跳没回 device_bound: true"; exit 1; }
post api/device/heartbeat "$UT" '7c 没认领的设备不许自称已绑定' HeartbeatReply
if grep -q '^device_bound: true' tmp-out.txt; then
  echo "7c 未认领设备自称已绑定，手机会把该显示的绑定码藏掉"; exit 1
fi
echo "7b/7c device_bound 两向都对"

enc GetReportRequest "child_id:\"$CHILD\" since_ms:0 until_ms:99999999999999"
post api/parent/get-report "$PT" '8 家长报表' GetReportReply

enc UploadBankRequest "child_id:\"$CHILD\" drafts:{subject:MATH knowledge_point:\"烟测\" stem:\"1+1=?\" options:\"2\" options:\"3\" correct_index:0 explanation:\"个位相加\"}"
post api/parent/upload-bank "$PT" '9 家长上传私有题' UploadBankReply

enc NextQuestionRequest 'grade:99'
post api/device/next-question "$DT" '10 空题库要 fail-closed（只回规则不回题）' NextQuestionReply

# 静态资源是 native 才炸的那一类：JVM 从 target/classes 读，镜像里靠 AOT 把 classpath:/static 打进去，
# 少打进去只会白屏，不会有任何测试变红。这一步是唯一拦得住它的。
# 做法是把 src/main/resources/static 里的每个文件都向 exe 要一遍：镜像烘焙的是出包那一刻的文件名，
# 前端重新构建却没重新出包时哈希对不上，这里会直接 404/403 报错，而不是让你悄悄看到一版旧页面。
printf '%-26s' '11 web 产物在 native 里能出'
html=$(curl -s -o tmp-index.html -w '%{http_code}' "$API/")
[ "$html" = 200 ] || { echo " 首页 http=$html"; exit 1; }
# 首页引用的路径必须是 200（用 grep -oE，别用 sed s|…|：竖线已经是分隔符时 \| 不再是"或"）。
for p in $(grep -oE '"/(assets|favicon)[^"]*"' tmp-index.html | tr -d '"'); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$API$p")
  [ "$code" = 200 ] || { echo " $p http=$code"; exit 1; }
done
count=0
for file in $(cd src/main/resources/static && find . -type f ! -name index.html | sed 's|^\./||'); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$API/$file")
  [ "$code" = 200 ] || { echo " $file http=$code（前端产物和镜像不同步：先 npm run build 再重新出包）"; exit 1; }
  count=$((count + 1))
done
# 目录形状的请求是 native 才会露馅的那一半：JVM 上读目录抛异常，而镜像里 classpath 目录节点
# 真能读出一个"每行一个文件名"的清单——修复前 GET /assets/ 就是 200 加整包 js/css 名单。
# 所以这里既钉状态码，也把正文再查一遍：只要有以 .js/.css/.png 结尾的行出现，清单就没堵住。
for dir in /assets/ /assets; do
  body=$(curl -s -w '\n%{http_code}' "$API$dir")
  code=${body##*$'\n'}
  [ "$code" = 404 ] || { echo " $dir 本该 404，实得 $code（产物目录清单泄露）"; exit 1; }
  if printf '%s' "${body%$'\n'*}" | grep -qE '\.(js|css|png|svg|woff2?)$'; then
    echo " $dir 的响应体里出现了文件名，目录清单没堵住"; exit 1
  fi
done
# 缺文件必须是 404 而不是 500：兜底那条 Exception 会把 NoResourceFoundException 吞成服务端故障。
nofile=$(curl -s -o /dev/null -w '%{http_code}' "$API/assets/no-such-file-here.js")
[ "$nofile" = 404 ] || { echo " 缺失产物本该 404，实得 $nofile"; exit 1; }
# 拼 URL 一律 "$API/$file"，file 不带前导斜杠；多一个斜杠就成了 //assets/...，
# 那种畸形路径归兜底链拒绝，看着就像产物没打进去——这一步我第一次就是这么误判的。
malformed=$(curl -s -o /dev/null -w '%{http_code}' "$API//assets/x.js")
[ "$malformed" = 401 ] || [ "$malformed" = 403 ] || { echo " // 畸形路径本该被兜底链拒，实得 $malformed"; exit 1; }
deny=$(curl -s -o /dev/null -w '%{http_code}' "$API/WEB-INF/web.xml")
[ "$deny" = 401 ] || [ "$deny" = 403 ] || { echo " 未知路径本该被拒，实得 $deny"; exit 1; }
echo " 首页 200 · 产物 $count 个全 200 · 目录形状 404 · 缺文件 404 · //畸形路径 $malformed · 未知路径 $deny"

# 角色分档。这一步一次验两件只有原生二进制才会暴露的事：
# 一是新增的 AccountSummary/ListAccountsReply 这些生成类没注册反射时，JVM 全绿而镜像里解回空；
# 二是 admin 档位那道闸——家长令牌要是能进去，数据隔离就等于只做在按钮上。
: "${QUIZGATE_ADMIN_USERNAME:?要验角色分档就得先 export 管理员账号}"
: "${QUIZGATE_ADMIN_PASSWORD:?再 export 管理员口令}"
enc LoginRequest "username:\"$QUIZGATE_ADMIN_USERNAME\" password:\"$QUIZGATE_ADMIN_PASSWORD\""
post api/parent/login - '12a 管理员登录' LoginReply
AT=$(field parent_token)
[ "${AT#admin-}" != "$AT" ] || { echo " 管理员令牌的 kind 不是 admin（$AT），两档人还是共用一种令牌"; exit 1; }

enc ListAccountsRequest ''
post api/admin/list-accounts "$AT" '12b 管理员列全量账号' ListAccountsReply
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/admin/list-accounts" \
  -H "X-Quizgate-Token: $PT" -H 'Content-Type: application/x-protobuf' --data-binary @tmp-req.bin)
[ "$code" = 401 ] || [ "$code" = 403 ] || { echo " 家长令牌竟然进了 admin 档位，实得 $code"; exit 1; }
echo "12c 家长令牌进 admin 档位被拒（http=$code）"

# 13：新家长那条自助注册链路，必须在镜像里真跑一遍。
# Register/CreateChild 这几个消息类是 S6 才加的：ProtoReflectionHints 少注册一个字段时
# JVM 全绿、镜像里解回空报文，只有真打一次才看得见。另外"一枚码只换一个账号"这条断言，
# 在服务层靠 @Transactional 回滚兜着——注释掉了测试才会红，冒烟这一遍是第二个网。
enc CreateInviteRequest ''
post api/admin/create-invite "$AT" '13a 管理员发一枚注册码' CreateInviteReply
INVITE=$(field code)
REG="smokereg$(date +%s)"

enc RegisterRequest "invite_code:\"$INVITE\" username:\"$REG\" password:\"$QUIZGATE_PARENT_PASSWORD\" display_name:\"烟测家长\""
post api/parent/register - '13b 凭码自助注册（这条路径免令牌）' RegisterReply
RT=$(field parent_token)
[ "${RT#parent-}" != "$RT" ] || { echo " 注册出来的账号拿的不是 parent 令牌（$RT）"; exit 1; }

enc CreateChildRequest 'display_name:"烟测娃" grade:2'
post api/parent/create-child "$RT" '13c 新家长建第一个孩子' CreateChildReply

enc LoginRequest "username:\"$REG\" password:\"$QUIZGATE_PARENT_PASSWORD\""
post api/parent/login - '13d 新家长重登只看自己' LoginReply
own=$(grep -c 'child_id:' tmp-out.txt || true)
[ "$own" = 1 ] || { echo " 新注册的家长登录回了 $own 个孩子，数据隔离没做在查询上"; exit 1; }

# 13f：页面刷新后重取列表。前端把令牌存在 sessionStorage，孩子列表却只在登录应答里给过一次，
# 少了这条接口，家长按一次 F5 就会看到"这个账号下还没有孩子档案"——谎报。
enc ListChildrenRequest ''
post api/parent/list-children "$RT" '13f 刷新后重取孩子列表' ListChildrenReply
again=$(grep -c 'child_id:' tmp-out.txt || true)
[ "$again" = "$own" ] || { echo " 重取列表和登录那一次不一致：$own → $again"; exit 1; }

enc RegisterRequest "invite_code:\"$INVITE\" username:\"${REG}x\" password:\"$QUIZGATE_PARENT_PASSWORD\""
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/parent/register" \
  -H 'Content-Type: application/x-protobuf' --data-binary @tmp-req.bin)
[ "$code" = 400 ] || { echo " 同一枚码又注册出第二个账号，实得 $code"; exit 1; }
echo "13e 重放注册码被拒（http=$code）"

# 14：联动这一跳。家长写的规则要能在闸门那侧读回、家长传的题要能回显、孩子机型号要能透进家长列表。
# 这几步用的全是 S7 新增的消息类（GetRuleReply/BankQuestion/带机型的 ChildSummary）——
# 反射元数据少注册一个字段时 JVM 全绿、镜像里解回空报文，只有真打一遍才看得见。
CID=$(field child_id)   # 13f 那份列表里的第一个孩子，属于刚自助注册出来的家长
enc SaveRuleRequest "child_id:\"$CHILD\" rule:{threshold_minutes:22 daily_limit_minutes:90 grace_minutes:3 enabled:true}"
post api/parent/save-rule "$PT" '14a 家长写一条非默认规则' RuleSnapshot
WVER=$(sed -n 's/.*version: \([0-9]*\).*/\1/p' tmp-out.txt | head -1)
[ -n "$WVER" ] || { echo " 14a 没解出 version，后面的比对没有意义"; exit 1; }

enc GetRuleRequest "child_id:\"$CHILD\""
post api/parent/get-rule "$PT" '14b 家长读回刚写那一条' GetRuleReply
RTHR=$(sed -n 's/.*threshold_minutes: \([0-9]*\).*/\1/p' tmp-out.txt | head -1)
RVER=$(sed -n 's/.*version: \([0-9]*\).*/\1/p' tmp-out.txt | head -1)
[ "$RTHR" = "22" ] || { echo " 写进去 22、读回 ${RTHR:-空}，规则页显示的就不是库里的真值"; exit 1; }
[ "$RVER" = "$WVER" ] || { echo " 读回的版本和写入那次不一致：$WVER → ${RVER:-空}"; exit 1; }

enc HeartbeatRequest "accessibility_connected:true accumulated_ms:60000 known_rule_version:$((WVER - 1))"
post api/device/heartbeat "$DT" '14c 闸门心跳拿到家长写的值' HeartbeatReply
GTHR=$(sed -n 's/.*threshold_minutes: \([0-9]*\).*/\1/p' tmp-out.txt | head -1)
[ "$GTHR" = "22" ] || { echo " 家长写的 22 没落到闸门（闸门读的是 ${GTHR:-空}），联动就断在这一跳"; exit 1; }

enc ListChildrenRequest ''
post api/parent/list-children "$PT" '14d 家长列表带孩子机型' ListChildrenReply
grep -q 'device_model: "Pixel4"' tmp-out.txt || { echo " 机型没透到家长列表"; exit 1; }
grep -q 'android_api_level: 30' tmp-out.txt || { echo " enroll 上报的 API 等级没落到家长列表"; exit 1; }

enc UploadBankRequest "child_id:\"$CID\" drafts:{subject:MATH knowledge_point:\"linkage\" stem:\"linkage 3+4\" options:\"6\" options:\"7\" options:\"8\" correct_index:1 explanation:\"count one finger\"}"
post api/parent/upload-bank "$RT" '14e 新家长给自己孩子传一题' UploadBankReply

# 断言里的文字一律用 ASCII：protoc 的文本解码会把非 ASCII 转成 \350\201\224 这种八进制转义，
# 拿中文原文去 grep 一定不命中——这一步我第一次就是这么误判成"回显丢了"的。
enc ListBankRequest "child_id:\"$CID\""
post api/parent/list-bank "$RT" '14f 家长回显自己传的题' ListBankReply
grep -q 'stem: "linkage 3+4"' tmp-out.txt || { echo " 刚传的题没出现在回显里，家长看不见自己传了什么"; exit 1; }
grep -q 'correct_index: 1' tmp-out.txt || { echo " 回显的正确下标不是家长选的那一个"; exit 1; }
grep -q 'question_id: ' tmp-out.txt || { echo " 回显没带题号，家长改不了也删不了"; exit 1; }

# 14g：越界的两条读路径。前一条刚建好数据，正适合拿来当"别人家的孩子"。
for probe in "GetRuleRequest:get-rule" "ListBankRequest:list-bank"; do
  msg=${probe%%:*}; path=${probe##*:}
  enc "$msg" "child_id:\"$CHILD\""
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/parent/$path" \
    -H "X-Quizgate-Token: $RT" -H 'Content-Type: application/x-protobuf' --data-binary @tmp-req.bin)
  [ "$code" != 200 ] || { echo " 别的家长竟然读到了孩子 1 的 $path，越界只挡在按钮上"; exit 1; }
  echo "14g $path 越界读被拒（http=$code）"
done

# 15：拍照识题这一串在 native 里只验三件事——新消息编得解得、verified 那列读得回来、门真的在拦。
# 真引擎默认关着（QUIZGATE_EXTRACTOR_ENABLED=false），所以 15a 期望的是"200 + 一句人话"而不是题目；
# 这条路径同时也在证明：开关没打开时，exe 里那个 HTTP 客户端一次都没被用到。
enc ScanBankRequest "child_id:\"$CID\" image:\"\\xff\\xd8\\xff\\xd9\" image_mime:\"image/jpeg\""
post api/parent/scan-bank "$RT" '15a 引擎没启用时回一句人话' ScanBankReply
grep -q 'engine_message' tmp-out.txt || { echo " 开关关着却没回话，家长只会看到一个空结果"; exit 1; }

enc ScanBankRequest "child_id:\"$CID\" image_mime:\"image/jpeg\""
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/parent/scan-bank" \
  -H "X-Quizgate-Token: $RT" -H 'Content-Type: application/x-protobuf' --data-binary @tmp-req.bin)
[ "$code" = 400 ] || { echo " 空图期望 400，实得 $code"; exit 1; }
echo "15b 空图被挡在门口（http=$code）"

# 题号从 14f 的回显里取：那是 14e 这位家长亲手传的那一道，动它不碰别人的数据。
enc ListBankRequest "child_id:\"$CID\""
post api/parent/list-bank "$RT" '15c 取题号用的回显' ListBankReply
QID=$(field question_id)
[ -n "$QID" ] || { echo " 回显里没有题号，后面的核对步骤没法做"; exit 1; }

enc VerifyQuestionRequest "question_id:\"$QID\" verified:false"
post api/parent/verify-question "$RT" '15d 家长驳回自己那道题' VerifyQuestionReply
enc ListBankRequest "child_id:\"$CID\""
post api/parent/list-bank "$RT" '15e 驳回之后回显' ListBankReply
# proto3 的 bool=false 是默认值，文本格式压根不打印 verified 这一行，所以"驳回成功"的样子就是它不见了。
# 反例：驳回没落库时这行会以 verified: true 出现，所以这个取反的 grep 是真能变红的。
if grep -q 'verified: true' tmp-out.txt; then
  echo " 驳回了却没写成未核对，闸门照样会抽它"; exit 1
fi

enc VerifyQuestionRequest "question_id:\"$QID\" verified:true"
post api/parent/verify-question "$RT" '15f 家长重新核对通过' VerifyQuestionReply
enc ListBankRequest "child_id:\"$CID\""
post api/parent/list-bank "$RT" '15g 核对之后回显' ListBankReply
grep -q 'verified: true' tmp-out.txt || { echo " 核对通过没落到库里"; exit 1; }

enc DeleteQuestionRequest 'question_id:"1"'
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/api/parent/delete-question" \
  -H "X-Quizgate-Token: $RT" -H 'Content-Type: application/x-protobuf' --data-binary @tmp-req.bin)
[ "$code" != 200 ] || { echo " 内置题被删掉了，闸门从此没题可出"; exit 1; }
echo "15h 内置题删不动（http=$code）"

enc DeleteQuestionRequest "question_id:\"$QID\""
post api/parent/delete-question "$RT" '15i 删掉自己那道题' DeleteQuestionReply
enc ListBankRequest "child_id:\"$CID\""
post api/parent/list-bank "$RT" '15j 删完之后回显' ListBankReply
if grep -q 'stem: "linkage 3+4"' tmp-out.txt; then
  echo " 删掉的题还在回显里"; exit 1
fi
echo "15j 删掉的题不再出现在回显里"

# ── 16：拆闸门告警。原生镜像里要验的四件事：新消息编得解得、去重那句 not-exists 在 native 下
# 照样安静返回 false、按户筛真的拦得住、device.accessibility_connected 那个可空列读得回来。
RUN=$(( $(date +%s) ))
NOW=$(( RUN * 1000 ))
# 事件编号带本次运行的秒戳：库里那次运行留下的同 id 行会被安静判成"重复"，
# 于是第二次跑 16a 就绿不成了——这不是去重坏了，是脚本自己不够幂等（和 install_id 带时间戳同一个道理）。
enc GateEventReportRequest "events:{event_id:\"smoke-gate-$RUN-1\" kind:ACCESSIBILITY_OFF occurred_at_ms:$((NOW - 60000)) detail:\"accessibility turned off\"}"
post api/device/gate-event "$DT" '16a 设备上报一条动手记录' GateEventReportReply
grep -q 'accepted: 1' tmp-out.txt || { echo " 第一条没落库"; exit 1; }

# 16b：整批重传。反例是去重失效——那时这里会变成 accepted: 1，家长看到的就是一模一样的告警刷屏。
# 注意 accepted 为 0 时这一行压根不打印（proto3 的 int32 默认值不进文本格式，和 15e 那个 bool 同一件事），
# 所以"没收下"的见证是两句：duplicated 那一句要在，accepted 那一句要不在。只 grep 'accepted: 0' 会永远红。
post api/device/gate-event "$DT" '16b 同一条重传' GateEventReportReply
grep -q 'duplicated: 1' tmp-out.txt || { echo " 重传没被认出来"; exit 1; }
if grep -q 'accepted:' tmp-out.txt; then
  echo " 重传被当成新记录收下了，家长会看到重复告警"; exit 1
fi

enc GateEventReportRequest "events:{event_id:\"smoke-gate-$RUN-2\" kind:NOTIFICATIONS_OFF occurred_at_ms:$NOW detail:\"notifications turned off\"}"
post api/device/gate-event "$DT" '16c 第二条另一种 kind' GateEventReportReply

# 取某个孩子在 ListChildrenReply 里的那一块：dad 名下不止一个孩子，
# 拿整份应答 grep 会读到别的孩子留下的值，那种绿不算数。
child_block() { awk -v pat="child_id: \"$1\"" 'index($0, pat){f=1} f{print} f && /^}/{f=0}' tmp-out.txt; }

enc ListAlertsRequest ''
post api/parent/list-alerts "$PT" '16d 家长读到自家孩子的动手记录' ListAlertsReply
grep -q 'ACCESSIBILITY_OFF' tmp-out.txt || { echo " 无障碍那条没回到家长手上"; exit 1; }
grep -q 'NOTIFICATIONS_OFF' tmp-out.txt || { echo " 通知那条没回到家长手上"; exit 1; }

# 16e：越户读。RT 是 14e 那位新注册的家长，自家孩子一条记录都没有，这里必须读不到 child 1 那两条。
# 反例：归属筛选漏掉时，这一份应答会带着 ACCESSIBILITY_OFF 回来。
enc ListAlertsRequest ''
post api/parent/list-alerts "$RT" '16e 邻户读不到别人家的告警' ListAlertsReply
if grep -q 'ACCESSIBILITY_OFF\|NOTIFICATIONS_OFF' tmp-out.txt; then
  echo " 别的家长读到了 child 1 的告警"; exit 1
fi
echo " 邻户读到的告警为空"

# 16f：三值门。proto3 里 0 不打印，所以顺序刻意是"先读出 2、再读出 1"——两步都是正值，都能看见。
# 反例：可空列被读成 false（家长端就会把"还没上报过"谎报成"孩子关了无障碍"），第一步就变红。
enc HeartbeatRequest "accessibility_connected:false accumulated_ms:0 known_rule_version:0"
post api/device/heartbeat "$DT" '16f 心跳说无障碍被关了' HeartbeatReply
enc ListChildrenRequest ''
post api/parent/list-children "$PT" '16g 家长列表读出无障碍=关' ListChildrenReply
child_block "$CHILD" | grep -q 'accessibility_state: 2' || { echo " 自家孩子的无障碍状态没读出「被关了」"; exit 1; }

enc HeartbeatRequest "accessibility_connected:true accumulated_ms:0 known_rule_version:0"
post api/device/heartbeat "$DT" '16h 心跳说无障碍恢复了' HeartbeatReply
enc ListChildrenRequest ''
post api/parent/list-children "$PT" '16i 家长列表读出无障碍=开' ListChildrenReply
child_block "$CHILD" | grep -q 'accessibility_state: 1' || { echo " 自家孩子的无障碍状态没读出「连着」"; exit 1; }
echo "16j 三值门在原生二进制上成立（不知道 / 关 / 开 各归各位）"

rm -f tmp-req.bin tmp-resp.bin tmp-out.txt tmp-index.html
