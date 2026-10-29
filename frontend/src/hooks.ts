import { useCallback, useEffect, useRef, useState } from "react";

export const messageOf = (error: unknown) =>
  error instanceof Error ? error.message : "操作未完成，请重试。";

/** 只允许当前轮请求更新页面；切页后的响应与卸载后的轮询不会污染新知识库。 */
export function useQuery<T>(loader: () => Promise<T>, pollMs = 0) {
  const [data, setData] = useState<T>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  const refresh = useCallback(() => setRevision((n) => n + 1), []);
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    async function load(first: boolean) {
      if (first) setLoading(true);
      try {
        const result = await loaderRef.current();
        if (alive) {
          setData(result);
          setError("");
        }
      } catch (e) {
        if (alive) setError(messageOf(e));
      } finally {
        if (alive) {
          setLoading(false);
          if (pollMs)
            timer = setTimeout(() => {
              if (!document.hidden) void load(false);
              else timer = setTimeout(() => void load(false), pollMs);
            }, pollMs);
        }
      }
    }
    void load(true);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [revision, pollMs]);
  return { data, setData, loading, error, refresh };
}

export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function fileSize(bytes: number) {
  return bytes < 1024
    ? `${bytes} B`
    : bytes < 1048576
      ? `${(bytes / 1024).toFixed(1)} KB`
      : `${(bytes / 1048576).toFixed(1)} MB`;
}
export function dateLabel(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}
