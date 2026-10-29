/** 与 Java ApiModels 对齐的接口契约；不把密码或云模型 Key 放进业务状态。 */
export interface User {
  id: string;
  username: string;
  tenantId: string;
}
export interface KnowledgeBase {
  id: string;
  tenantId: string;
  name: string;
  description: string;
  ownerId: string;
  createdAt: string;
}
export type DocumentStatus =
  | "PENDING"
  | "INDEXING"
  | "READY"
  | "FAILED"
  | "DELETE_PENDING"
  | "DELETING"
  | "DELETE_FAILED"
  | "DELETED";
export interface KnowledgeDocument {
  id: string;
  knowledgeBaseId: string;
  filename: string;
  sizeBytes: number;
  sha256: string;
  status: DocumentStatus;
  chunkCount: number;
  attempts: number;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}
export interface Source {
  reference: number;
  documentId: string;
  filename: string;
  chunkIndex: number;
  score: number | null;
  excerpt: string;
  downloadUrl: string;
}
export interface Answer {
  status: "ANSWERED" | "NO_EVIDENCE";
  answer: string;
  sources: Source[];
  elapsedMs: number;
}
export interface Member {
  id: string;
  username: string;
  role: "OWNER" | "READER";
}
export interface Api {
  mode: "live" | "demo";
  user: User;
  onExpired?: () => void;
  listKnowledgeBases(): Promise<KnowledgeBase[]>;
  createKnowledgeBase(
    name: string,
    description: string,
  ): Promise<KnowledgeBase>;
  listDocuments(
    kb: string,
    page: number,
    size: number,
  ): Promise<KnowledgeDocument[]>;
  upload(kb: string, file: File): Promise<KnowledgeDocument>;
  retryDocument(id: string): Promise<void>;
  deleteDocument(id: string): Promise<void>;
  download(id: string): Promise<Blob>;
  listMembers(kb: string): Promise<Member[]>;
  grant(kb: string, username: string): Promise<void>;
  revoke(kb: string, username: string): Promise<void>;
  ask(kb: string, question: string, signal?: AbortSignal): Promise<Answer>;
  dispose(): void;
}
