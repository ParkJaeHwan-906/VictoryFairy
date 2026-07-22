CREATE TABLE `users` (
	`id`	BIGINT	NOT NULL,
	`name`	VARCHAR(30)	NULL,
	`tel`	VARCHAR(11)	NULL,
	`email`	VARCHAR(100)	NULL,
	`gender`	TINYINT	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `player_records` (
	`id`	BIGINT	NOT NULL,
	`player_id`	BIGINT	NOT NULL,
	`game_id`	BIGINT	NOT NULL,
	`bat_result_id`	BIGINT	NOT NULL,
	`created_at`	DATETIME	NULL
);

CREATE TABLE `chatrooms` (
	`id`	BIGINT	NOT NULL,
	`team_id`	BIGINT	NOT NULL,
	`name`	VARCHAR(255)	NULL,
	`participants`	BIGINT	NULL,
	`deleted_at`	DATETIME	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `CopyOfplayer_records` (
	`id`	BIGINT	NOT NULL,
	`player_id`	BIGINT	NOT NULL,
	`game_id`	BIGINT	NOT NULL,
	`pitch_result_id`	BIGINT	NOT NULL,
	`created_at`	DATETIME	NULL
);

CREATE TABLE `character_items` (
	`id`	BIGINT	NOT NULL,
	`character_id`	BIGINT	NOT NULL,
	`name`	VARCHAR(100)	NULL,
	`img`	VARCHAR(255)	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `players` (
	`id`	BIGINT	NOT NULL,
	`team_id`	BIGINT	NOT NULL,
	`name`	VARCHAR(100)	NULL,
	`average`	DOUBLE	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `user_character_inventory` (
	`id`	BIGINT	NOT NULL,
	`user_account_id`	BIGINT	NOT NULL,
	`character_id`	BIGINT	NOT NULL,
	`active`	TINYINT	NULL,
	`creted_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `Untitled` (
	`id`	BIGINT	NOT NULL,
	`name`	VARCHAR(100)	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `quizzes` (
	`id`	BIGINT	NOT NULL,
	`team_id`	BIGINT	NOT NULL,
	`player_id`	BIGINT	NOT NULL,
	`content`	TEXT	NULL,
	`answer`	INT	NULL,
	`score`	DOUBLE	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `bat_result` (
	`id`	BIGINT	NOT NULL,
	`name`	VARCHAR(100)	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `quiz_users_submit` (
	`id`	BIGINT	NOT NULL,
	`user_account_id`	BIGINT	NOT NULL,
	`quiz_id`	BIGINT	NOT NULL,
	`submit`	INT	NULL,
	`created_at`	DATETIME	NULL
);

CREATE TABLE `crwaled_data` (
	`id`	BIGINT	NOT NULL,
	`domain`	BIGINT	NULL,
	`Field2`	VARCHAR(255)	NULL
);

CREATE TABLE `Untitled3` (
	`id`	BIGINT	NOT NULL,
	`name`	VARCHAR(100)	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `users_refreshtoken` (
	`id`	BIGINT	NOT NULL,
	`user_account_id`	BIGINT	NOT NULL,
	`refreshtoken`	VARCHAR(255)	NULL,
	`expired_at`	DATETIME	NULL,
	`created_at`	DATETIME	NULL
);

CREATE TABLE `chats` (
	`id`	BIGINT	NOT NULL,
	`chatroom_id`	BIGINT	NOT NULL,
	`user_account_id`	BIGINT	NOT NULL,
	`content`	TEXT	NULL,
	`blind`	TINYINT	NULL,
	`deleted_at`	DATETIME	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `users_account` (
	`id`	BIGINT	NOT NULL,
	`Field`	VARCHAR(36)	NULL,
	`user_id`	BIGINT	NOT NULL,
	`nickname`	VARCHAR(100)	NULL,
	`password`	VARCHAR(255)	NULL,
	`exit`	DATETIME	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `user_support_team` (
	`id`	BIGINT	NOT NULL,
	`user_account_id`	BIGINT	NOT NULL,
	`team_id`	BIGINT	NOT NULL,
	`oppose`	DATETIME	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `user_character_item_inventory` (
	`id`	BIGINT	NOT NULL,
	`user_account_id`	BIGINT	NOT NULL,
	`character_item_id`	BIGINT	NOT NULL,
	`active`	TINYINT	NULL,
	`creted_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `user_support_player` (
	`id`	BIGINT	NOT NULL,
	`user_account_id`	BIGINT	NOT NULL,
	`player_id`	BIGINT	NOT NULL,
	`oppose`	DATETIME	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `characters` (
	`id`	BIGINT	NOT NULL,
	`name`	varchar(100)	NULL,
	`img`	VARCHAR(255)	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `teams` (
	`id`	BIGINT	NOT NULL,
	`name`	VARCHAR(100)	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `user_bq` (
	`id`	BIGINT	NOT NULL,
	`user_account_id`	BIGINT	NOT NULL,
	`bq_score`	DOUBLE	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `stardiums` (
	`id`	BIGINT	NOT NULL,
	`name`	VARCHAR(100)	NULL,
	`address`	VARCHAR(255)	NULL,
	`active`	TINYiNT	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

CREATE TABLE `games` (
	`id`	BIGINT	NOT NULL,
	`stardium_id`	BIGINT	NOT NULL,
	`status_id`	BIGINT	NOT NULL,
	`home_team_id`	BIGINT	NOT NULL,
	`away_team_id`	BIGINT	NOT NULL,
	`game_date`	DATE	NULL,
	`away_score`	INT	NULL,
	`home_score`	INT	NULL,
	`created_at`	DATETIME	NULL,
	`updated_at`	DATETIME	NULL
);

ALTER TABLE `users` ADD CONSTRAINT `PK_USERS` PRIMARY KEY (
	`id`
);

ALTER TABLE `player_records` ADD CONSTRAINT `PK_PLAYER_RECORDS` PRIMARY KEY (
	`id`
);

ALTER TABLE `chatrooms` ADD CONSTRAINT `PK_CHATROOMS` PRIMARY KEY (
	`id`
);

ALTER TABLE `CopyOfplayer_records` ADD CONSTRAINT `PK_COPYOFPLAYER_RECORDS` PRIMARY KEY (
	`id`
);

ALTER TABLE `character_items` ADD CONSTRAINT `PK_CHARACTER_ITEMS` PRIMARY KEY (
	`id`
);

ALTER TABLE `players` ADD CONSTRAINT `PK_PLAYERS` PRIMARY KEY (
	`id`
);

ALTER TABLE `user_character_inventory` ADD CONSTRAINT `PK_USER_CHARACTER_INVENTORY` PRIMARY KEY (
	`id`
);

ALTER TABLE `Untitled` ADD CONSTRAINT `PK_UNTITLED` PRIMARY KEY (
	`id`
);

ALTER TABLE `quizzes` ADD CONSTRAINT `PK_QUIZZES` PRIMARY KEY (
	`id`
);

ALTER TABLE `bat_result` ADD CONSTRAINT `PK_BAT_RESULT` PRIMARY KEY (
	`id`
);

ALTER TABLE `quiz_users_submit` ADD CONSTRAINT `PK_QUIZ_USERS_SUBMIT` PRIMARY KEY (
	`id`
);

ALTER TABLE `crwaled_data` ADD CONSTRAINT `PK_CRWALED_DATA` PRIMARY KEY (
	`id`
);

ALTER TABLE `Untitled3` ADD CONSTRAINT `PK_UNTITLED3` PRIMARY KEY (
	`id`
);

ALTER TABLE `users_refreshtoken` ADD CONSTRAINT `PK_USERS_REFRESHTOKEN` PRIMARY KEY (
	`id`
);

ALTER TABLE `chats` ADD CONSTRAINT `PK_CHATS` PRIMARY KEY (
	`id`
);

ALTER TABLE `users_account` ADD CONSTRAINT `PK_USERS_ACCOUNT` PRIMARY KEY (
	`id`
);

ALTER TABLE `user_support_team` ADD CONSTRAINT `PK_USER_SUPPORT_TEAM` PRIMARY KEY (
	`id`
);

ALTER TABLE `user_character_item_inventory` ADD CONSTRAINT `PK_USER_CHARACTER_ITEM_INVENTORY` PRIMARY KEY (
	`id`
);

ALTER TABLE `user_support_player` ADD CONSTRAINT `PK_USER_SUPPORT_PLAYER` PRIMARY KEY (
	`id`
);

ALTER TABLE `characters` ADD CONSTRAINT `PK_CHARACTERS` PRIMARY KEY (
	`id`
);

ALTER TABLE `teams` ADD CONSTRAINT `PK_TEAMS` PRIMARY KEY (
	`id`
);

ALTER TABLE `user_bq` ADD CONSTRAINT `PK_USER_BQ` PRIMARY KEY (
	`id`
);

ALTER TABLE `stardiums` ADD CONSTRAINT `PK_STARDIUMS` PRIMARY KEY (
	`id`
);

ALTER TABLE `games` ADD CONSTRAINT `PK_GAMES` PRIMARY KEY (
	`id`
);

