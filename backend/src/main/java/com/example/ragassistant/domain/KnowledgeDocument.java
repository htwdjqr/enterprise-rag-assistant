package com.example.ragassistant.domain;

import java.time.Instant;

/**
 * 一份上传原文件的 MySQL 快照，也是 Worker 领取的任务数据，不是切块后的 Spring AI Document。
 * record 不可变：claim 更新数据库后，本对象的 status 仍是查询时的旧状态，需要重新查询才能看到新值。
 * 不直接作为 API 返回，storageKey 属于内部实现细节；页面使用 ApiModels.DocumentView。
 *
 * @param id 文档 UUID，用于接口定位及派生稳定的块 ID
 * @param knowledgeBaseId 文档所属知识库
 * @param tenantId 文档所属租户，同时进入向量元数据，用于隔离检索和删除范围
 * @param originalFilename 用户上传的展示名称，不是磁盘路径
 * @param storageKey 服务器生成的 UUID 文件名，由 LocalFileStorage 解析为实际路径
 * @param sizeBytes 原文件字节数，与提取后的字数不同
 * @param sha256 原文件的 SHA-256 摘要，不是文本向量
 * @param status 查询时的文档状态，只有 READY 能参与正常问答检索
 * @param chunkCount 成功发布时登记的文本块数，不表示失败任务已写入多少向量
 * @param attempts 每次后台领取任务时增加的次数，索引和删除共用该计数
 * @param errorMessage 最近一次失败的安全提示，重新领取或重试时清空
 * @param createdAt 上传登记时间
 * @param updatedAt 最近一次状态更新等写操作的时间，也参与任务排序
 */
public record KnowledgeDocument(String id, String knowledgeBaseId, String tenantId,
        String originalFilename, String storageKey, long sizeBytes, String sha256,
        DocumentStatus status, int chunkCount, int attempts, String errorMessage,
        Instant createdAt, Instant updatedAt) { }
