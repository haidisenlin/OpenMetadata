# 术语与用户的 Persona 上下文绑定

术语可以绑定一个默认 Persona，并为指定用户配置不同的 Persona。多个术语可以复用同一个 Persona；同一账号查询不同术语时，不需要切换账号或修改活动 Persona。

## 配置入口

1. 在 Persona 中配置并启用所需的 AI 上下文。
2. 打开术语详情，进入 **AI 上下文** 页签，点击 **编辑**。
3. 选择默认 Persona。需要差异化显示时，添加用户覆盖并选择用户及对应 Persona。
4. 保存。每个术语最多配置 100 个用户覆盖，同一用户只能出现一次。默认 Persona 可以留空；用户和 Persona 必须成对选择。

编辑需要术语的 `EditAll` 权限。版本历史只读。清除默认 Persona 或删除用户覆盖并保存，即可取消相应绑定。

例如，UPH 术语默认使用 `uph-show`，用户甲覆盖为 `uph-detail`，用户乙没有覆盖：甲查询匹配 UPH 的结果时使用 `uph-detail`，乙使用 `uph-show`。其他术语可以配置自己的显示上下文。

## 选择和权限规则

MCP 使用经过认证的当前用户 UUID，依次选择：

1. 当前术语中匹配该用户的 `contextPersonaOverrides`。
2. 当前术语的 `contextPersona`。
3. 两者均不存在时，不附加术语 Persona 上下文。

所选 Persona 不可访问、已删除或已禁用时，返回 `unavailable`，不会改用另一个 Persona。绑定本身不授予权限；普通用户仍需具备现有 Persona 成员权限，管理员和机器人沿用现有访问规则。

身份来自 MCP 认证信息，不能通过工具参数指定另一个用户。如果多个调用方共用同一个服务账号令牌，它们都会匹配该服务账号的配置。要区分用户，需要让 MCP 认证链传递各自的用户身份。

## API 字段

术语创建、更新和 JSON Patch 支持以下可选字段。引用中的 `id` 是实体 UUID；服务端校验类型、实体存在状态及重复用户，并补齐实体引用。

```json
{
  "contextPersona": { "id": "<default-persona-uuid>", "type": "persona" },
  "contextPersonaOverrides": [
    {
      "user": { "id": "<user-uuid>", "type": "user" },
      "persona": { "id": "<user-persona-uuid>", "type": "persona" }
    }
  ]
}
```

字段保存在术语现有 JSON 中，使用现有版本与变更记录机制，无需新增数据库列。已有术语没有绑定时保持原有行为。

## MCP 调用链

内置 MCP 在以下只读工具返回明确的术语引用后，重新授权并读取术语绑定，通过与 `get_persona_context` 共用的读取器加载所选上下文：

- `search_metadata`、`semantic_search`
- `get_entity_details`、`find_context`
- `get_term_relation_graph`
- `resolve_record_binding`、`find_record_related_assets`

匹配以结构化的术语引用为依据，不从描述、标签字符串或 RDF 文本推断术语。已有 UUID 时按 UUID 授权和读取，避免索引中的旧名称导致选错术语。仅有名称时按全限定名称读取。

原查询结果和分页信息保留，新增 `personaContexts`，包含选中的 Persona、命中术语、选择来源（`user` 或 `term`）和状态：

| 状态 | 含义 |
| --- | --- |
| `loaded` | 已附加上下文首段；内容在 `context` 中 |
| `deferred` | 当前响应剩余空间不足；按 `nextCall` 读取完整首段 |
| `unavailable` | 所选上下文当前无法读取 |

同一个 Persona 在单次响应内只加载一次。超过处理数量限制时，通过 `personaContextsTruncated` 标识。完整上下文过长时保留分段号、`hasMore` 和指纹；调用方按 `nextCall` 中的 `get_persona_context` 参数续读。自动附加上下文不保证客户端一定采用其中的显示方式，客户端仍需使用工具返回的上下文生成回答。

MCP 输出移除用户覆盖数组及对应变更记录，只返回当前调用选中的上下文。术语管理 API 保留完整配置供维护者管理。独立的 Python ontology MCP 不包含在本次内置 MCP 实现中。

## 上线要求

需要同时构建和部署更新后的服务端、内置 MCP 和前端，并使用重新生成的模型。仅修改本地源码不会改变正在运行的服务。首次上线后，可分别用两个用户调用同一术语的 `get_entity_details`，验证返回的 `personaContexts` 选择及权限结果。
