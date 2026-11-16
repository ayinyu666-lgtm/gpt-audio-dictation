# 手机会话快照：独立本地归档工具

这是独立本地归档工具，**不抓取网页，不验证快照是否真的完整，也不连接桌面 writer**。输入必须由外部提供，`verification` 是提供者的明确声明，不是程序独立核验。没有经过验证的采集来源时不要用真实内容宣称已经完整备份。

## 使用

Node.js 22 或更高版本。先选择一个专用、空的绝对路径；初始化会创建标记，后续命令只接受带正确标记的目录。不要把现有归档目录作为 root。

```powershell
node tools/mobile-archive/cli.mjs init --root 'C:\Temp\gad-archive-prototype'
node tools/mobile-archive/cli.mjs import --root 'C:\Temp\gad-archive-prototype' --snapshot 'C:\Temp\snapshot.json'
node tools/mobile-archive/cli.mjs resume --root 'C:\Temp\gad-archive-prototype'
node tools/mobile-archive/test.mjs
```

快照 JSON（文字、ID、来源仅为示例）：

```json
{
  "schemaVersion": 1,
  "verification": {
    "complete": true,
    "messagesStable": true,
    "answersComplete": true,
    "method": "外部核验方式的说明"
  },
  "source": "来源命名空间",
  "conversationId": "来源中的稳定会话ID",
  "title": "GAD｜示例主题｜2026-10-30 09-00｜A7K2Q9-01",
  "sessionStart": "2026-10-30T09:00:00+01:00",
  "messages": [
    { "id": "稳定消息ID-1", "role": "user", "text": "原文" },
    { "id": "稳定消息ID-2", "role": "assistant", "text": "回答" }
  ]
}
```

标题的课程、课次码、开始日期和本地时间决定分类；`sessionStart` 必须带时区偏移并与标题时间一致。`source + conversationId` 定义会话身份，消息 ID 在该会话内必须唯一且重导入时不变。非空 user/assistant 消息按来源顺序独立保存，允许连续 user、连续 assistant 及任意首尾角色，不推测消息之间的配对关系。若最后一条是 user，该消息文件标记“后续回答：快照中尚无”，结果附带 `answerStatus: no_following_assistant_in_snapshot`；它只描述快照末尾，不能判断某条较早的 user 是否已有对应回答。相同文本可以是不同消息。多分会话使用相同课程、课次码和完整开始时间，以及不同会话 ID 和分会话号。

`verification.complete`、`messagesStable` 和 `answersComplete` 仍须为 true：提供者声明快照已完整采集、消息 ID 稳定、快照中已取得的助手回答均已完成；这不要求末尾 user 已有回答。如果回答正在生成或快照不完整，不得作此声明。

## 输出与恢复

- `queue/` 保存不可变的完整快照；提交先完成队列文件，再处理归档。`resume` 检查并重试所有队列任务，并逐项返回结果；任一失败则命令退出码非零。
- `index/` 固定课次及来源会话的归属和完整消息清单；相同会话的消息、顺序、标题、课程或时间变化会报 `conflict`。当前不支持增量追加、回答修改或自动合并冲突。
- `courses/` 按课程和课次分目录、按会话分目录，每条消息写为单独 Markdown，文件名由消息 ID 的 SHA-256 决定；正文记录顺序。`receipts/` 只在该任务所有消息写入确认后生成。
- 文件先写入同目录临时文件并 `fsync`，通过硬链接以不覆盖方式发布；已存在时逐字核对。同一快照重试不会重复写入，内容不同不会覆盖。中断可能留下 `.pending-*` 临时文件，它们不作为正式记录；队列和已写成的文件保留，运行 `resume` 继续。
- `import` 返回 `created`、`duplicate` 和任务 ID；它们统计消息文件。队列和回执保留用于重试与核查，不做自动清理。

## 限制

来源的真实性、全量、消息 ID 稳定性和回答完成状态由提供者负责；声明不构成证据。当前无网页登录采集、自动分类映射、合并 writer 或开机一次启动，也没有课堂级跨会话完整性判定。原 writer 的格式与本原型不同，尚未设计迁移。时间偏移不校验地区夏令时。只验证普通进程中断与重试，未证明突然断电或文件系统硬件故障下目录项持久性；硬链接不受支持的文件系统会报错。归档根目录必须由可信用户控制，不支持多人共享或手工编辑内部索引；出现冲突应保留队列并人工检查。
