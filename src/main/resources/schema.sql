-- H2 方言。迁移到 PostgreSQL 时 identity 与 RAND() 是唯一要改的两处，
-- 数据访问层刻意只用这套最朴素的 SQL。

create table if not exists parent_account (
  id identity primary key,
  username varchar(64) not null unique,
  password_hash varchar(100) not null,
  display_name varchar(64) not null,
  role varchar(16) not null
);

create table if not exists child_profile (
  id identity primary key,
  parent_id bigint not null,
  display_name varchar(64) not null,
  grade int not null
);

create table if not exists device (
  id identity primary key,
  install_id varchar(64) not null unique,
  child_id bigint,
  device_model varchar(64),
  android_api_level int,
  bind_code varchar(8),
  last_heartbeat_at bigint,
  accessibility_connected boolean
);

-- create table if not exists 对已经存在的表不会补列，所以老库必须单独走这一句。
-- 没有它，上一轮注册的设备的 android_api_level 会永久缺席，家长端显示成"未上报"而不是历史值。
alter table device add column if not exists android_api_level int;
-- 同一个坑的第二处：这一列缺席时 SELECT_DEVICE 直接报错，整个家长后台打不开，
-- 而不只是少一个字段——所以补列必须在启动就跑，不能等真有人配了无障碍状态再说。
alter table device add column if not exists accessibility_connected boolean;

create table if not exists question_bank (
  id identity primary key,
  owner_parent_id bigint,
  scope_child_id bigint,
  name varchar(64) not null
);

create table if not exists question (
  id identity primary key,
  bank_id bigint not null,
  subject varchar(16) not null,
  grade int not null,
  knowledge_point varchar(64) not null,
  stem varchar(512) not null,
  correct_index int not null,
  explanation varchar(1024) not null,
  verified boolean not null default true
);

-- 拍照识别出来的题先落库成"没人担保过答案"，闸门只抽核对过的。
-- 默认 true 是有意的：内置题和家长手打的题在录入那一刻就已经是出题人确认过的，
-- 只有 scan 那条路径会显式写 false。老库里的存量行因此不会被这道新门突然抽干。
-- create table if not exists 不补列，所以既有库必须走这一句。
alter table question add column if not exists verified boolean not null default true;

-- 选项单独成行：题库文本来自家长上传，任何分隔符方案都可能被内容本身吃掉。
create table if not exists question_option (
  question_id bigint not null,
  idx int not null,
  text varchar(256) not null,
  primary key (question_id, idx)
);

create table if not exists rule (
  child_id bigint primary key,
  threshold_minutes int not null,
  daily_limit_minutes int not null,
  grace_minutes int not null,
  enabled boolean not null,
  version bigint not null
);

create table if not exists rule_watched_package (
  child_id bigint not null,
  package_name varchar(128) not null,
  primary key (child_id, package_name)
);

create table if not exists usage_event (
  event_id varchar(36) primary key,
  child_id bigint not null,
  device_id bigint not null,
  occurred_at_ms bigint not null,
  app_package varchar(128) not null,
  played_ms bigint not null,
  correct_count int not null,
  wrong_count int not null,
  interception_failed boolean not null
);

-- 孩子动手拆闸门的证据。和 usage_event 同一个去重套路：设备断网期间攒下的那几条会反复重传。
-- reported_at_ms 单独留一列：设备报的"发生时刻"和服务端"什么时候收到"是两件事，
-- 攒了三天才连上网的那些事件，occurred_at 很旧但确实是刚才是第一次送达——家长列表按哪个排，得能说清。
-- event_id 比 usage_event 那一列宽：设备没填编号时服务端补的键是 "gen-" 前缀加 UUID，共 40 个字符。
-- 照抄 varchar(36) 会让那条兜底路径自己 500——它平时不走到，只有脏数据来了才走到，所以更要留够。
create table if not exists gate_event (
  event_id varchar(64) primary key,
  child_id bigint not null,
  device_id bigint not null,
  kind varchar(32) not null,
  occurred_at_ms bigint not null,
  reported_at_ms bigint not null,
  detail varchar(200) not null
);

-- 服务端第二本账：出题目时就落一行，判定后回填。
-- 它同时是判分凭据——只有被真正下发过的题才允许提交答案，否则接口会退化成答案预言机。
create table if not exists question_ask (
  id identity primary key,
  child_id bigint not null,
  question_id bigint not null,
  asked_at_ms bigint not null,
  selected_index int,
  correct boolean
);

create table if not exists auth_token (
  token varchar(64) primary key,
  kind varchar(16) not null,
  subject_id bigint not null,
  created_at_ms bigint not null
);

-- 注册闸门：一码一号。没有这张表的话，自助注册等于"任何拿到 URL 的人都能建号"。
-- used_by 非空即已消耗，同时留下"谁注册的"这条审计线；code 由 SecureRandom 发，不做唯一约束之外的推断。
create table if not exists invite_code (
  code varchar(16) primary key,
  created_by bigint not null,
  used_by bigint,
  created_at_ms bigint not null
);

create index if not exists idx_question_lookup on question (grade, knowledge_point);
create index if not exists idx_usage_child on usage_event (child_id, occurred_at_ms);
create index if not exists idx_gate_event_child on gate_event (child_id, occurred_at_ms);
