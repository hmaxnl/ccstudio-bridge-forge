/**
 * Removes the leading slash from a path
 * @param path
 * @returns
 */
export const stripLeadingSlash = (path: string) => {
  return path.replace(/^\//, "");
};

/**
 * Removes the trailing slash from a path
 * @param path
 * @returns
 */
export const stripTrailingSlash = (path: string) => {
  return path.replace(/\/$/, "");
};

/**
 * Ensures the given path starts with a slash
 * @param path
 * @returns
 */
export const normalizeRootPath = (path: string) => {
  if (!path.startsWith("/")) {
    path = "/" + path;
  }

  return path;
};

/**
 * Removes the root (base) from a given path, if exists. Effectively a "subtract" for paths, bound to the start of the path. Both paths should start with a slash.
 * @param path The path to remove the `root` from
 * @param root The root to remove from the start of `path`
 * @returns
 */
export const removeRoot = (path: string, root: string) => {
  root = stripTrailingSlash(root);

  if (path.startsWith(root)) {
    return path.slice(root.length);
  }

  return path;
};
