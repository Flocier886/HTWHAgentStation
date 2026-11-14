-- Schema reconstructed from the twelve MyBatis mapper files at f975868.
-- The MySQL container creates ai_agent_station before running this script.
CREATE TABLE IF NOT EXISTS ai_agent (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64) NOT NULL,
  agent_name VARCHAR(255), description TEXT, channel VARCHAR(64),
  strategy VARCHAR(64), status INT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_agent_flow_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(64),
  client_id VARCHAR(64), client_name VARCHAR(255), client_type VARCHAR(64),
  sequence INT, step_prompt LONGTEXT,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_flow_agent_sequence (agent_id, sequence),
  KEY idx_flow_client_id (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_agent_draw_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, config_id VARCHAR(64),
  config_name VARCHAR(255), description TEXT, agent_id VARCHAR(64),
  config_data LONGTEXT, version INT, status INT DEFAULT 1,
  create_by VARCHAR(64), update_by VARCHAR(64),
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_draw_config_id (config_id), KEY idx_draw_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_agent_task_schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, agent_id BIGINT,
  task_name VARCHAR(255), description TEXT, cron_expression VARCHAR(128),
  task_param LONGTEXT, status INT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_task_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_client (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, client_id VARCHAR(64) NOT NULL,
  client_name VARCHAR(255), description TEXT, status INT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_client_id (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_client_api (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, api_id VARCHAR(64) NOT NULL,
  base_url VARCHAR(1024), api_key VARCHAR(1024),
  completions_path VARCHAR(255), embeddings_path VARCHAR(255),
  status INT DEFAULT 1, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_api_id (api_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_client_model (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, model_id VARCHAR(64) NOT NULL,
  api_id VARCHAR(64), model_name VARCHAR(255), model_type VARCHAR(64),
  status INT DEFAULT 1, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_model_id (model_id), KEY idx_model_api_id (api_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_client_config (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type VARCHAR(64), source_id VARCHAR(64),
  target_type VARCHAR(64), target_id VARCHAR(64), ext_param LONGTEXT,
  status INT DEFAULT 1, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_config_source (source_type, source_id),
  KEY idx_config_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_client_advisor (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, advisor_id VARCHAR(64) NOT NULL,
  advisor_name VARCHAR(255), advisor_type VARCHAR(64), order_num INT,
  ext_param LONGTEXT, status INT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_advisor_id (advisor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_client_system_prompt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, prompt_id VARCHAR(64) NOT NULL,
  prompt_name VARCHAR(255), prompt_content LONGTEXT, description TEXT,
  status INT DEFAULT 1, create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_prompt_id (prompt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_client_tool_mcp (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, mcp_id VARCHAR(64) NOT NULL,
  mcp_name VARCHAR(255), transport_type VARCHAR(32),
  transport_config LONGTEXT, request_timeout INT, status INT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_mcp_id (mcp_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_client_rag_order (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, rag_id VARCHAR(64),
  rag_name VARCHAR(255), knowledge_tag VARCHAR(255), status INT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_rag_id (rag_id), KEY idx_rag_knowledge_tag (knowledge_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
