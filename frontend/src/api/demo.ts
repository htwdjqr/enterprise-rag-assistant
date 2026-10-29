import type {
  Answer,
  Api,
  KnowledgeBase,
  KnowledgeDocument,
  Member,
  User,
} from "../types";
import { ApiError } from "./http";
import { withDemoState } from "./demo-storage";

/**
 * 只用于明确标注的“交互演示”。资料和原文件保存到浏览器 IndexedDB，刷新后保留，不调用模型或后端。
 * 与真实 API 实现同一个接口，页面组件不需要混入测试分支。
 */
const now = () => new Date().toISOString();
const people: User[] = ["alice", "bob"].map((username) => ({
  id: username,
  username,
  tenantId: "demo-company",
}));
const handbook =
  "# 示例员工手册\n\n国内出差住宿报销上限为每人每天 500 元，需提供住宿发票及出差审批单。\n\n员工请年假需提前 3 个工作日申请，直属主管审批后生效。\n\n办公设备故障请在内部服务台创建工单。";
type DemoDocument = KnowledgeDocument & {
  file: Blob;
  text: string;
  deadline?: number;
};

interface DemoState {
  bases: KnowledgeBase[];
  docs: DemoDocument[];
  members: Map<string, Set<string>>;
}

/** 首次访问时才生成示例数据；删除过的示例文件不会因刷新重新出现。 */
function seedState(): DemoState {
  const bases: KnowledgeBase[] = [
    {
      id: "employee",
      name: "员工制度与办事指南",
      description: "差旅报销、休假申请与日常办公支持，让每个问题都有据可查。",
      ownerId: "alice",
      tenantId: "demo-company",
      createdAt: now(),
    },
  ];
  const docs: DemoDocument[] = [
    {
      id: "handbook",
      knowledgeBaseId: "employee",
      filename: "员工手册（示例）.md",
      sizeBytes: new Blob([handbook]).size,
      sha256: "demo",
      status: "READY",
      chunkCount: 3,
      attempts: 1,
      errorMessage: null,
      createdAt: now(),
      updatedAt: now(),
      file: new Blob([handbook], { type: "text/markdown" }),
      text: handbook,
    },
  ];
  const members = new Map<string, Set<string>>([
    ["employee", new Set(["bob"])],
  ]);

  return { bases, docs, members };
}

const transact = <T>(change: (state: DemoState) => T) =>
  withDemoState(seedState, change);

export class DemoApi implements Api {
  readonly mode = "demo" as const;
  user: User;
  onExpired?: () => void;
  constructor(username = "alice") {
    this.user = people.find((p) => p.username === username) || people[0];
  }
  private check(state: DemoState, kb: string, write = false) {
    const base = state.bases.find((b) => b.id === kb);
    if (
      !base ||
      (base.ownerId !== this.user.id &&
        !state.members.get(kb)?.has(this.user.id))
    )
      throw new ApiError(404, "该知识库不存在或没有访问权限。");
    if (write && base.ownerId !== this.user.id)
      throw new ApiError(403, "只有创建人可以管理知识库。");
    return base;
  }
  private document(state: DemoState, id: string, write = false) {
    const doc = state.docs.find((d) => d.id === id);
    if (!doc) throw new ApiError(404, "文档不存在。");
    this.check(state, doc.knowledgeBaseId, write);
    return doc;
  }
  private settle(state: DemoState) {
    for (const doc of state.docs)
      if (doc.deadline && Date.now() >= doc.deadline) {
        doc.status =
          doc.status === "DELETE_PENDING"
            ? "DELETED"
            : doc.text.trim()
              ? "READY"
              : "FAILED";
        doc.errorMessage =
          doc.status === "FAILED"
            ? "示例文件没有可提取的文字，请删除后重新上传。"
            : null;
        doc.chunkCount =
          doc.status === "READY"
            ? Math.max(1, Math.ceil(doc.text.length / 600))
            : 0;
        doc.deadline = undefined;
        doc.updatedAt = now();
      }
    state.docs = state.docs.filter((doc) => doc.status !== "DELETED");
  }
  async listKnowledgeBases() {
    return transact((state) => {
      return state.bases
        .filter(
          (b) =>
            b.ownerId === this.user.id ||
            state.members.get(b.id)?.has(this.user.id),
        )
        .map((b) => ({ ...b }));
    });
  }
  async createKnowledgeBase(name: string, description: string) {
    return transact((state) => {
      const base = {
        id: crypto.randomUUID(),
        name,
        description,
        ownerId: this.user.id,
        tenantId: this.user.tenantId,
        createdAt: now(),
      };
      state.bases.unshift(base);
      state.members.set(base.id, new Set());
      return { ...base };
    });
  }
  async listDocuments(kb: string, page: number, size: number) {
    return transact((state) => {
      this.check(state, kb);
      this.settle(state);
      return state.docs
        .filter((d) => d.knowledgeBaseId === kb && d.status !== "DELETED")
        .slice(page * size, (page + 1) * size)
        .map((d) => ({ ...d }));
    });
  }
  async upload(kb: string, file: File) {
    const text = /\.(md|txt)$/i.test(file.name)
      ? await file.text()
      : "二进制文件的示例索引。演示模式不执行 PDF / Word 解析。";
    return transact((state) => {
      this.check(state, kb, true);
      const doc: DemoDocument = {
        id: crypto.randomUUID(),
        knowledgeBaseId: kb,
        filename: file.name,
        sizeBytes: file.size,
        sha256: "demo",
        status: "PENDING",
        chunkCount: 0,
        attempts: 1,
        errorMessage: null,
        createdAt: now(),
        updatedAt: now(),
        file,
        text,
        deadline: Date.now() + 1500,
      };
      state.docs.unshift(doc);
      return { ...doc };
    });
  }
  async retryDocument(id: string) {
    return transact((state) => {
      const doc = this.document(state, id, true);
      if (!["FAILED", "DELETE_FAILED"].includes(doc.status))
        throw new ApiError(409, "只有失败的文档可以重试。");
      doc.status =
        doc.status === "DELETE_FAILED" ? "DELETE_PENDING" : "PENDING";
      doc.attempts++;
      doc.deadline = Date.now() + 1500;
    });
  }
  async deleteDocument(id: string) {
    return transact((state) => {
      const doc = this.document(state, id, true);
      doc.status = "DELETE_PENDING";
      doc.deadline = Date.now() + 1200;
    });
  }
  async download(id: string) {
    return transact((state) => {
      const doc = this.document(state, id);
      if (doc.status.startsWith("DELET"))
        throw new ApiError(404, "文件已删除。");
      return doc.file;
    });
  }
  async listMembers(kb: string): Promise<Member[]> {
    return transact((state) => {
      const base = this.check(state, kb, true);
      return people
        .filter(
          (p) => p.id === base.ownerId || state.members.get(kb)?.has(p.id),
        )
        .map((p) => ({
          id: p.id,
          username: p.username,
          role: p.id === base.ownerId ? "OWNER" : "READER",
        }));
    });
  }
  async grant(kb: string, username: string) {
    return transact((state) => {
      this.check(state, kb, true);
      const user = people.find((p) => p.username === username);
      if (!user)
        throw new ApiError(404, "示例模式仅包含 alice 和 bob 两个账号。");
      state.members.get(kb)!.add(user.id);
    });
  }
  async revoke(kb: string, username: string) {
    return transact((state) => {
      this.check(state, kb, true);
      state.members.get(kb)?.delete(username);
    });
  }
  async ask(
    kb: string,
    question: string,
    signal?: AbortSignal,
  ): Promise<Answer> {
    await new Promise<void>((resolve) => setTimeout(resolve, 650));
    if (signal?.aborted) throw new ApiError(408, "请求已取消。");
    return transact((state) => {
      this.check(state, kb);
      this.settle(state);
      const ready = state.docs.filter(
        (d) => d.knowledgeBaseId === kb && d.status === "READY",
      );
      const topic = /住宿|差旅|报销/.test(question)
        ? "住宿"
        : /请假|年假/.test(question)
          ? "年假"
          : /工单|电脑|设备/.test(question)
            ? "工单"
            : "";
      const doc = topic ? ready.find((d) => d.text.includes(topic)) : undefined;
      if (!doc)
        return {
          status: "NO_EVIDENCE",
          answer:
            "示例资料中没有足够依据回答这个问题。可以试试询问住宿报销、年假申请或 IT 支持。",
          sources: [],
          elapsedMs: 650,
        };
      const excerpt = doc.text
        .split("\n")
        .filter((line) => line.includes(topic))
        .join("\n");
      return {
        status: "ANSWERED",
        answer: `${excerpt}\n\n以上为示例资料摘录。[1]`,
        elapsedMs: 650,
        sources: [
          {
            reference: 1,
            documentId: doc.id,
            filename: doc.filename,
            chunkIndex: 0,
            score: 0.88,
            excerpt,
            downloadUrl: `/api/documents/${doc.id}/file`,
          },
        ],
      };
    });
  }
  dispose() {
    /* 退出只结束页面会话，本地演示资料仍保留，便于再次进入或切换身份。 */
  }
}
