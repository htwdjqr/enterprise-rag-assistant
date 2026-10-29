package com.example.ragassistant.domain;

/**
 * 持久化在 knowledge_document.status 中的状态；名称须与 Repository SQL 中的字符串保持一致。
 * 常规索引：PENDING → INDEXING → READY，异常进入 FAILED，手动重试后回到 PENDING。
 * READY 是唯一可参与问答的状态；申请删除后立即失去新检索的候选资格，不必等待物理清理。
 */
public enum DocumentStatus {
    /** 原文件和任务已登记，等待 Worker 领取；并不表示已经开始调用模型。 */
    PENDING,
    /** Worker 已领取，正在提取、分块或写向量，尚未对问答发布。 */
    INDEXING,
    /** 向量写入链路成功并发布，可参与有权限的问答检索。 */
    READY,
    /** 索引失败，原文件保留供手动重试，不能参与正常问答检索。 */
    FAILED,
    /** 已提交删除请求，等待 Worker 清理向量和原文件。 */
    DELETE_PENDING,
    /** Worker 已领取删除任务，正在执行物理清理。 */
    DELETING,
    /** 删除部分失败，保留错误提示，手动重试后回到 DELETE_PENDING。 */
    DELETE_FAILED,
    /** 物理清理已完成，业务记录保留但从普通文档列表中隐藏。 */
    DELETED
}
