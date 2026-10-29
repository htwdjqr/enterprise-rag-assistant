import { ApiError } from "./http";

/**
 * IndexedDB 能直接保存 Blob、Map 和 Set，适合保存演示文件；不需要把 PDF 转成
 * Base64 放进容量较小的 localStorage。这个数据库只保存演示资料，不保存登录凭据。
 */
function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("zhixu-interactive-demo", 1);
    request.onupgradeneeded = () =>
      request.result.createObjectStore("workspace");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("演示数据库被其他页面占用"));
  });
}

/**
 * 一次事务内读取最新状态、修改并保存，防止轮询或多个标签页覆盖彼此的数据。
 * change 必须同步执行：文件读取、模拟等待等异步操作放在事务外，否则事务会提前结束。
 * 只有事务提交成功才返回结果；配额不足等错误会回滚，页面不会误报上传成功。
 */
export async function withDemoState<S, T>(
  seed: () => S,
  change: (state: S) => T,
): Promise<T> {
  let db: IDBDatabase | undefined;
  try {
    db = await openDatabase();
    return await new Promise<T>((resolve, reject) => {
      const transaction = db!.transaction("workspace", "readwrite");
      const store = transaction.objectStore("workspace");
      const request = store.get("current");
      let result: T;
      let failure: unknown;
      request.onsuccess = () => {
        try {
          const state = (request.result as S | undefined) ?? seed();
          result = change(state);
          store.put(state, "current");
        } catch (error) {
          failure = error;
          transaction.abort();
        }
      };
      transaction.oncomplete = () => resolve(result);
      transaction.onabort = () => reject(failure ?? transaction.error);
      transaction.onerror = () => {
        /* 统一由 onabort 返回事务错误。 */
      };
    });
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(
      0,
      error instanceof DOMException && error.name === "QuotaExceededError"
        ? "浏览器存储空间不足，未保存本次操作。请删除不需要的演示文件后重试。"
        : "无法读取或保存本地演示数据，请检查浏览器是否允许此网站使用本地存储后重试。",
    );
  } finally {
    db?.close();
  }
}
