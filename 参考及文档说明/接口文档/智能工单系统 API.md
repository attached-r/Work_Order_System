> **OAS 3.1 不完整导出**
> 有 5 个语义问题无法完整表示。
> - PUT /user/{userId}/roles · requestBody · UNREPRESENTABLE_KEYWORD (minItems)
> - GET /permission/tree · response 200 example · SEARCH_BUDGET_EXCEEDED
> - GET /workorder/{id} · response 200 example · SEARCH_BUDGET_EXCEEDED
> - GET /workorder/page · response 200 example · SEARCH_BUDGET_EXCEEDED
> - GET /user/page · response 200 example · SEARCH_BUDGET_EXCEEDED

# 智能工单系统 API

**版本:** 1.0

智能工单管理系统接口文档

# 用户管理

## 修改用户信息

**PUT** `/user/{userId}`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `userId` | path | integer/int64 | 是 |  |

#### 请求参数 `userId`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `UpdateUserDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `realName` | string | 是 | 真实姓名 |
| `phone` | string | 否 | 联系电话,可空 |
| `departmentId` | integer/int64 | 否 | 所属部门ID,可空表示不调整 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "realName": "",
  "phone": "",
  "departmentId": 0
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 删除用户

**DELETE** `/user/{userId}`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `userId` | path | integer/int64 | 是 |  |

#### 请求参数 `userId`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 启停用账号

**PUT** `/user/{userId}/status`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `userId` | path | integer/int64 | 是 |  |

#### 请求参数 `userId`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `UpdateUserStatusDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `status` | integer/int32 | 是 | 账号状态:0禁用 1启用 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "status": 0
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 分配角色

**PUT** `/user/{userId}/roles`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `userId` | path | integer/int64 | 是 |  |

#### 请求参数 `userId`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `AssignRoleDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `roleIds` | integer/int64[] (截断) | 是 | 角色ID列表,至少一个 |
| `roleIds[]` | integer/int64 | 否 |  |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "roleIds": [
    0
  ]
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 重置密码

**PUT** `/user/{userId}/password`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `userId` | path | integer/int64 | 是 |  |

#### 请求参数 `userId`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `ResetPasswordDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `newPassword` | string | 是 | 新密码 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "newPassword": "aaaaaa"
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 调整所属部门

**PUT** `/user/{userId}/department`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `userId` | path | integer/int64 | 是 |  |

#### 请求参数 `userId`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `AssignDepartmentDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `departmentId` | integer/int64 | 是 | 部门ID |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "departmentId": 0
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 注册

**POST** `/user/register`

### 请求参数

_无请求参数_

### 请求体

**Content-Type:** `application/json` · **类型:** `RegisterDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 登录账号 |
| `password` | string | 是 | 密码 |
| `confirmPassword` | string | 是 | 确认密码,需与密码一致 |
| `realName` | string | 是 | 真实姓名 |
| `phone` | string | 否 | 联系电话,可空 |
| `departmentId` | integer/int64 | 否 | 所属部门ID,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "username": "",
  "password": "aaaaaa",
  "confirmPassword": "a",
  "realName": "",
  "phone": "",
  "departmentId": 0
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 登出

**POST** `/user/logout`

### 请求参数

_无请求参数_

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 登录

**POST** `/user/login`

### 请求参数

_无请求参数_

### 请求体

**Content-Type:** `application/json` · **类型:** `LoginDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 账号 |
| `password` | string | 是 | 密码 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "username": "a",
  "password": "a"
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultString |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultString`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | string | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": "",
  "timestamp": 0
}
```

---

## 用户分页列表

**GET** `/user/page`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `current` | query | integer/int64 | 否 |  |
| `size` | query | integer/int64 | 否 |  |
| `keyword` | query | string | 否 |  |

#### 请求参数 `current`

**类型:** `integer/int64`

##### 请求示例

```
1
```

#### 请求参数 `size`

**类型:** `integer/int64`

##### 请求示例

```
10
```

#### 请求参数 `keyword`

**类型:** `string`

##### 请求示例

```

```

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultPageResultUserVO |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultPageResultUserVO`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | PageResultUserVO | 否 |  |
| `data.records` | UserVO[] | 否 |  |
| `data.records[]` | UserVO | 否 |  |
| `data.records[].userId` | integer/int64 | 否 | 用户id |
| `data.records[].username` | string | 否 | 用户名 |
| `data.records[].realName` | string | 否 | 真实姓名 |
| `data.records[].departmentId` | integer/int64 | 否 | 所属部门id |
| `data.records[].departmentName` | string | 否 | 所属部门名称 |
| `data.records[].status` | integer/int32 | 否 | 状态：1启用 0停用 |
| `data.records[].roles` | string[] | 否 | 角色标识列表,如 SUBMITTER |
| `data.records[].roles[]` | string | 否 |  |
| `data.records[].perms` | string[] | 否 | 权限码列表,如 workorder:review |
| `data.records[].perms[]` | string | 否 |  |
| `data.total` | integer/int64 | 否 |  |
| `data.current` | integer/int64 | 否 |  |
| `data.size` | integer/int64 | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

---

## 当前登录用户信息

**GET** `/user/me`

### 请求参数

_无请求参数_

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultUserVO |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultUserVO`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | UserVO | 否 |  |
| `data.userId` | integer/int64 | 否 | 用户id |
| `data.username` | string | 否 | 用户名 |
| `data.realName` | string | 否 | 真实姓名 |
| `data.departmentId` | integer/int64 | 否 | 所属部门id |
| `data.departmentName` | string | 否 | 所属部门名称 |
| `data.status` | integer/int32 | 否 | 状态：1启用 0停用 |
| `data.roles` | string[] | 否 | 角色标识列表,如 SUBMITTER |
| `data.roles[]` | string | 否 |  |
| `data.perms` | string[] | 否 | 权限码列表,如 workorder:review |
| `data.perms[]` | string | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {
    "userId": 0,
    "username": "",
    "realName": "",
    "departmentId": 0,
    "departmentName": "",
    "status": 0,
    "roles": [],
    "perms": []
  },
  "timestamp": 0
}
```

---

# 角色管理

## 分配权限

**PUT** `/role/{roleId}/permissions`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `roleId` | path | integer/int64 | 是 |  |

#### 请求参数 `roleId`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `AssignPermissionDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `permissionIds` | integer/int64[] | 是 | 权限ID列表,可为空列表表示清空 |
| `permissionIds[]` | integer/int64 | 否 |  |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "permissionIds": []
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 角色列表

**GET** `/role/list`

### 请求参数

_无请求参数_

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultListRole |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultListRole`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | Role[] | 否 |  |
| `data[]` | Role | 否 | 角色实体 |
| `data[].id` | integer/int64 | 否 | 主键 |
| `data[].roleCode` | string | 否 | 角色标识:SUBMITTER/REVIEWER/DISPATCHER/HANDLER/ADMIN(代码判断用) |
| `data[].roleName` | string | 否 | 角色名称:提单人/审核人/派单人/处理人/管理员(展示用) |
| `data[].remark` | string | 否 | 职责说明 |
| `data[].createTime` | string/date-time | 否 | 创建时间 |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": [],
  "timestamp": 0
}
```

---

# 部门管理

## 修改部门

**PUT** `/department/{id}`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | integer/int64 | 是 |  |

#### 请求参数 `id`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `DepartmentDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `deptCode` | string | 是 | 部门编码(唯一,代码判断用) |
| `deptName` | string | 是 | 部门名称 |
| `remark` | string | 否 | 备注,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "deptCode": "",
  "deptName": "",
  "remark": ""
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 删除部门

**DELETE** `/department/{id}`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | integer/int64 | 是 |  |

#### 请求参数 `id`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 新增部门

**POST** `/department`

### 请求参数

_无请求参数_

### 请求体

**Content-Type:** `application/json` · **类型:** `DepartmentDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `deptCode` | string | 是 | 部门编码(唯一,代码判断用) |
| `deptName` | string | 是 | 部门名称 |
| `remark` | string | 否 | 备注,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "deptCode": "",
  "deptName": "",
  "remark": ""
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 部门列表

**GET** `/department/list`

### 请求参数

_无请求参数_

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultListDepartment |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultListDepartment`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | Department[] | 否 |  |
| `data[]` | Department | 否 | 部门实体 |
| `data[].id` | integer/int64 | 否 | 主键 |
| `data[].deptCode` | string | 否 | 部门编码(唯一,代码判断用) |
| `data[].deptName` | string | 否 | 部门名称 |
| `data[].remark` | string | 否 | 备注 |
| `data[].createTime` | string/date-time | 否 | 创建时间 |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": [],
  "timestamp": 0
}
```

---

# 工单管理

## 创建工单

**POST** `/workorder`

### 请求参数

_无请求参数_

### 请求体

**Content-Type:** `application/json` · **类型:** `WorkOrderCreateDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `title` | string | 是 | 工单标题 |
| `content` | string | 否 | 工单描述,可空 |
| `orderType` | integer/int32 | 是 | 工单类型:1故障报修 2资源申请 3需求变更 |
| `priority` | integer/int32 | 是 | 优先级:1高 2中 3低 |
| `expireTime` | string/date-time | 否 | 超时时间,可空;不传则默认 72 小时后到期 |
| `resources` | WorkOrderResourceDTO[] | 否 | 资源明细列表,可空 |
| `resources[]` | WorkOrderResourceDTO | 否 | 工单资源明细 |
| `resources[].resourceType` | string | 是 | 资源类别:服务器/带宽/软件许可 等 |
| `resources[].resourceName` | string | 是 | 资源名称/规格描述 |
| `resources[].quantity` | number | 是 | 数量,必须大于0 |
| `resources[].unit` | string | 否 | 单位:台/MBps/个 等 |
| `resources[].remark` | string | 否 | 申请说明,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "title": "",
  "orderType": 1,
  "priority": 1,
  "content": "",
  "expireTime": "2024-01-01T00:00:00Z",
  "resources": []
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultLong |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultLong`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | integer/int64 | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": 0,
  "timestamp": 0
}
```

---

## 撤回/取消工单

**POST** `/workorder/{id}/withdraw`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | integer/int64 | 是 |  |

#### 请求参数 `id`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `WithdrawDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `remark` | string | 否 | 撤回/取消原因,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "remark": ""
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 审核工单

**POST** `/workorder/{id}/review`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | integer/int64 | 是 |  |

#### 请求参数 `id`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `ReviewDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `approved` | boolean | 是 | 是否通过:true 通过(→待派单) false 驳回(→已驳回) |
| `remark` | string | 否 | 审核意见/驳回原因,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "approved": true,
  "remark": ""
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 重新提交工单

**POST** `/workorder/{id}/resubmit`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | integer/int64 | 是 |  |

#### 请求参数 `id`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `WorkOrderCreateDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `title` | string | 是 | 工单标题 |
| `content` | string | 否 | 工单描述,可空 |
| `orderType` | integer/int32 | 是 | 工单类型:1故障报修 2资源申请 3需求变更 |
| `priority` | integer/int32 | 是 | 优先级:1高 2中 3低 |
| `expireTime` | string/date-time | 否 | 超时时间,可空;不传则默认 72 小时后到期 |
| `resources` | WorkOrderResourceDTO[] | 否 | 资源明细列表,可空 |
| `resources[]` | WorkOrderResourceDTO | 否 | 工单资源明细 |
| `resources[].resourceType` | string | 是 | 资源类别:服务器/带宽/软件许可 等 |
| `resources[].resourceName` | string | 是 | 资源名称/规格描述 |
| `resources[].quantity` | number | 是 | 数量,必须大于0 |
| `resources[].unit` | string | 否 | 单位:台/MBps/个 等 |
| `resources[].remark` | string | 否 | 申请说明,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "title": "",
  "orderType": 1,
  "priority": 1,
  "content": "",
  "expireTime": "2024-01-01T00:00:00Z",
  "resources": []
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 处理完成

**POST** `/workorder/{id}/process`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | integer/int64 | 是 |  |

#### 请求参数 `id`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `ProcessDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `remark` | string | 否 | 处理结果说明,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "remark": ""
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 派单/转派

**POST** `/workorder/{id}/dispatch`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | integer/int64 | 是 |  |

#### 请求参数 `id`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `DispatchDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `handlerId` | integer/int64 | 是 | 目标处理人用户ID |
| `remark` | string | 否 | 派单/转派说明,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "handlerId": 0,
  "remark": ""
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 验收工单

**POST** `/workorder/{id}/accept`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | integer/int64 | 是 |  |

#### 请求参数 `id`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

**Content-Type:** `application/json` · **类型:** `AcceptDTO` · **必填:** 是

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `approved` | boolean | 是 | 是否通过:true 通过(→已完成) false 退回(→处理中) |
| `remark` | string | 否 | 验收意见/退回原因,可空 |

#### 请求示例

**Content-Type:** `application/json`

```
{
  "approved": true,
  "remark": ""
}
```

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultVoid |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultVoid`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | unknown | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

#### 响应示例 `200`

**Content-Type:** `*/*`

```
{
  "code": 0,
  "message": "",
  "data": {},
  "timestamp": 0
}
```

---

## 工单详情

**GET** `/workorder/{id}`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | path | integer/int64 | 是 |  |

#### 请求参数 `id`

**类型:** `integer/int64`

##### 请求示例

```
0
```

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultWorkOrderDetailVO |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultWorkOrderDetailVO`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | WorkOrderDetailVO | 否 | 工单详情 |
| `data.id` | integer/int64 | 否 | 工单ID |
| `data.orderNo` | string | 否 | 工单编号 |
| `data.userId` | integer/int64 | 否 | 提单人ID |
| `data.departmentId` | integer/int64 | 否 | 归属部门ID |
| `data.handlerId` | integer/int64 | 否 | 处理人ID,派单前为空 |
| `data.title` | string | 否 | 工单标题 |
| `data.content` | string | 否 | 工单描述 |
| `data.orderType` | integer/int32 | 否 | 工单类型:1故障报修 2资源申请 3需求变更 |
| `data.priority` | integer/int32 | 否 | 优先级:1高 2中 3低 |
| `data.status` | integer/int32 | 否 | 状态:0待审核 1待派单 2处理中 3待验收 4已完成 5已驳回 6已取消 7已超时 |
| `data.statusDesc` | string | 否 | 状态名称 |
| `data.remark` | string | 否 | 备注/驳回原因/关闭说明 |
| `data.createTime` | string/date-time | 否 | 创建时间 |
| `data.updateTime` | string/date-time | 否 | 更新时间 |
| `data.expireTime` | string/date-time | 否 | 超时时间 |
| `data.resources` | WorkOrderResource[] | 否 | 资源明细 |
| `data.resources[]` | WorkOrderResource | 否 | 工单资源明细实体 |
| `data.resources[].id` | integer/int64 | 否 | 主键 |
| `data.resources[].orderId` | integer/int64 | 否 | 所属工单ID |
| `data.resources[].resourceType` | string | 否 | 资源类别:服务器/带宽/软件许可 等 |
| `data.resources[].resourceName` | string | 否 | 资源名称/规格描述 |
| `data.resources[].quantity` | number | 否 | 数量 |
| `data.resources[].unit` | string | 否 | 单位:台/MBps/个 等 |
| `data.resources[].remark` | string | 否 | 申请说明,可空 |
| `data.resources[].createTime` | string/date-time | 否 | 创建时间 |
| `data.logs` | WorkOrderOperateLog[] | 否 | 操作日志(按时间升序) |
| `data.logs[]` | WorkOrderOperateLog | 否 | 工单操作日志实体 |
| `data.logs[].id` | integer/int64 | 否 | 主键 |
| `data.logs[].orderId` | integer/int64 | 否 | 工单ID |
| `data.logs[].operatorId` | integer/int64 | 否 | 操作人ID(user.id) |
| `data.logs[].operateType` | integer/int32 | 否 | 操作事件:1提交 2审核通过 3审核驳回 4派单 5处理完成 6验收通过 7退回处理 8转派 9撤回/取消 10超时关闭 |
| `data.logs[].fromStatus` | integer/int32 | 否 | 变更前状态(0-7),首次提交为 NULL |
| `data.logs[].toStatus` | integer/int32 | 否 | 变更后状态(0-7) |
| `data.logs[].toUserId` | integer/int64 | 否 | 派单/转派目标处理人ID,可空 |
| `data.logs[].remark` | string | 否 | 操作备注,可空 |
| `data.logs[].createTime` | string/date-time | 否 | 操作时间 |
| `timestamp` | integer/int64 | 否 |  |

---

## 工单分页列表

**GET** `/workorder/page`

### 请求参数

| 参数名 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `current` | query | integer/int64 | 否 |  |
| `size` | query | integer/int64 | 否 |  |
| `status` | query | integer/int32 | 否 |  |
| `orderType` | query | integer/int32 | 否 |  |
| `keyword` | query | string | 否 |  |

#### 请求参数 `current`

**类型:** `integer/int64`

##### 请求示例

```
1
```

#### 请求参数 `size`

**类型:** `integer/int64`

##### 请求示例

```
10
```

#### 请求参数 `status`

**类型:** `integer/int32`

##### 请求示例

```
0
```

#### 请求参数 `orderType`

**类型:** `integer/int32`

##### 请求示例

```
0
```

#### 请求参数 `keyword`

**类型:** `string`

##### 请求示例

```

```

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultPageResultWorkOrderVO |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultPageResultWorkOrderVO`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | PageResultWorkOrderVO | 否 |  |
| `data.records` | WorkOrderVO[] | 否 |  |
| `data.records[]` | WorkOrderVO | 否 | 工单列表项 |
| `data.records[].id` | integer/int64 | 否 | 工单ID |
| `data.records[].orderNo` | string | 否 | 工单编号 |
| `data.records[].userId` | integer/int64 | 否 | 提单人ID |
| `data.records[].departmentId` | integer/int64 | 否 | 归属部门ID |
| `data.records[].handlerId` | integer/int64 | 否 | 处理人ID,派单前为空 |
| `data.records[].title` | string | 否 | 工单标题 |
| `data.records[].orderType` | integer/int32 | 否 | 工单类型:1故障报修 2资源申请 3需求变更 |
| `data.records[].priority` | integer/int32 | 否 | 优先级:1高 2中 3低 |
| `data.records[].status` | integer/int32 | 否 | 状态:0待审核 1待派单 2处理中 3待验收 4已完成 5已驳回 6已取消 7已超时 |
| `data.records[].statusDesc` | string | 否 | 状态名称 |
| `data.records[].createTime` | string/date-time | 否 | 创建时间 |
| `data.records[].updateTime` | string/date-time | 否 | 更新时间 |
| `data.records[].expireTime` | string/date-time | 否 | 超时时间 |
| `data.total` | integer/int64 | 否 |  |
| `data.current` | integer/int64 | 否 |  |
| `data.size` | integer/int64 | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

---

# 权限管理

## 权限树

**GET** `/permission/tree`

### 请求参数

_无请求参数_

### 请求体

_无请求体_

### 响应结构

| 状态码 | 说明 | Schema |
| --- | --- | --- |
| 200 | OK | ResultListPermissionVO |

#### 状态码 `200`

**Content-Type:** `*/*` · **类型:** `ResultListPermissionVO`

| 字段名 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `code` | integer/int32 | 否 |  |
| `message` | string | 否 |  |
| `data` | PermissionVO[] | 否 |  |
| `data[]` | PermissionVO | 否 | 权限树节点 |
| `data[].id` | integer/int64 | 否 | 权限ID |
| `data[].permCode` | string | 否 | 权限标识:如 workorder:review |
| `data[].permName` | string | 否 | 权限名称:如 审核工单 |
| `data[].parentId` | integer/int64 | 否 | 父权限ID,一级权限为 null |
| `data[].children` | PermissionVO[] | 否 | 子权限,无子权限时为空列表 |
| `data[].children[]` | PermissionVO (循环引用) | 否 |  |
| `timestamp` | integer/int64 | 否 |  |

---
