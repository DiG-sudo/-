CREATE TABLE IF NOT EXISTS `repository_agent_session` (
  `id` bigint NOT NULL COMMENT 'API会话ID',
  `context_summary` mediumtext DEFAULT NULL COMMENT '已压缩的旧对话语义',
  `summary_up_to_message_id` bigint DEFAULT NULL COMMENT '摘要已覆盖到的消息ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Repository Analysis Agent会话';

CREATE TABLE IF NOT EXISTS `repository_agent_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息ID',
  `session_id` bigint NOT NULL COMMENT 'API会话ID',
  `role` varchar(32) NOT NULL COMMENT 'user/assistant',
  `content` mediumtext NOT NULL COMMENT '消息正文',
  `repository_source_refs` json DEFAULT NULL COMMENT '最终回答引用的源码范围',
  `verification_status` varchar(32) DEFAULT NULL COMMENT '回答审计状态',
  `verification_reason` text DEFAULT NULL COMMENT '回答审计说明',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_repository_agent_message_session_id_id` (`session_id`, `id`),
  CONSTRAINT `fk_repository_agent_message_session`
    FOREIGN KEY (`session_id`) REFERENCES `repository_agent_session` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Repository Analysis Agent消息';
