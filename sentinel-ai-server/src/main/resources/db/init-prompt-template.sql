-- 初始化 Prompt 模板数据（首次运行时执行）

INSERT INTO t_prompt_template (template_key, template_name, content, description, status)
VALUES (
    'SQL_ANALYSIS_SYSTEM',
    'SQL分析-系统提示词',
    '你是一个资深的 MySQL DBA，拥有 10 年以上数据库性能优化经验。
你的任务是分析 SQL 语句的性能风险，给出准确的判断和可执行的优化建议。
你需要结合表结构、索引信息、数据量来做出判断。
分析时请使用中文回复。',
    '设定 AI 的角色和输出语言',
    1
) ON CONFLICT (template_key) DO NOTHING;

INSERT INTO t_prompt_template (template_key, template_name, content, description, status)
VALUES (
    'SQL_ANALYSIS_USER',
    'SQL分析-用户提示词模板',
    '请对以下 SQL 语句进行性能风险分析。

## SQL 信息
- **SQL 类型：** %s
- **来源位置：** %s
- **SQL 文本：**
```sql
%s
```

%s

%s

## 分析要求
1. 给出风险等级（riskLevel）：P0（紧急-必定慢SQL）/ P1（高危）/ P2（中危）/ P3（低危）/ P4（安全）
2. 判断是否能使用索引（canUseIndex），若能使用则说明预计使用哪个索引（indexUsed）
3. 估算扫描行数（estimatedScanRows）和执行时间（estimatedExecTimeMs，单位毫秒）
4. 列出所有性能问题（issues 数组），包括但不限于：
   - 是否走索引、是否全表扫描
   - LIKE 前导通配符
   - 函数包裹列导致索引失效
   - 隐式类型转换
   - 多表 JOIN 性能问题
   - 深分页问题
   - 动态 SQL 所有条件为空导致全表扫描的风险
   - 其他任何 MySQL 性能问题
5. 给出索引创建建议（indexSuggestions 数组，用 SQL 语句表示）
6. 给出 SQL 改写建议（rewriteSuggestions 数组，用改写后的 SQL 表示）
7. 给出综合分析说明（explanation）',
    '用户提示词模板，%s 占位符分别为：SQL类型、来源位置、SQL文本、DDL上下文、RAG上下文',
    1
) ON CONFLICT (template_key) DO NOTHING;
