import dotenv from 'dotenv';
dotenv.config();

// Fail fast in production if critical vars are missing or left at insecure defaults
if (process.env.NODE_ENV === 'production') {
  const required = ['JWT_SECRET', 'DB_HOST', 'DB_NAME', 'DB_USER', 'DB_PASSWORD'];
  const missing = required.filter((k) => !process.env[k]);
  if (missing.length > 0) {
    throw new Error(`Missing required environment variables: ${missing.join(', ')}`);
  }
  if (process.env.JWT_SECRET === 'change_me_in_production') {
    throw new Error('JWT_SECRET must be changed from its default value in production');
  }
}

export const config = {
  port:    parseInt(process.env.PORT    || '3000', 10),
  nodeEnv: process.env.NODE_ENV         || 'development',
  db: {
    host:     process.env.DB_HOST     || 'localhost',
    port:     parseInt(process.env.DB_PORT || '5432', 10),
    name:     process.env.DB_NAME     || 'psphere',
    user:     process.env.DB_USER     || 'postgres',
    password: process.env.DB_PASSWORD || '',
  },
  jwt: {
    secret:    process.env.JWT_SECRET    || 'change_me_in_production',
    expiresIn: process.env.JWT_EXPIRES_IN || '7d',
  },
  redis: {
    host: process.env.REDIS_HOST || 'localhost',
    port: parseInt(process.env.REDIS_PORT || '6379', 10),
  },
  llm: {
    provider: process.env.LLM_PROVIDER || 'ollama',
    baseUrl:  process.env.LLM_BASE_URL || 'http://localhost:11434',
    model:    process.env.LLM_MODEL    || 'llama3',
    apiKey:   process.env.LLM_API_KEY  || '',
  },
};
