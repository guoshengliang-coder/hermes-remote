import { describe, expect, it } from "vitest";
import type { SessionListItem } from "../hermes/types";
import { deriveProjects, disambiguatedLabels, inProject, isDefaultProjectPath, projectKeyOf, projectLabelOfPath } from "./projects";

const row = (id: string, last: number, extra: Partial<SessionListItem> = {}): SessionListItem => ({ id, last_active: last, ...extra });

describe("derived projects (Android ProjectDerivation.kt)", () => {
  it("groups by git repo root, falling back to cwd, ignoring trailing slashes", () => {
    expect(projectKeyOf({ git_repo_root: "/u/hermes-remote/", cwd: "/u/hermes-remote/web" })).toBe("/u/hermes-remote");
    expect(projectKeyOf({ git_repo_root: " ", cwd: "/u/notes/" })).toBe("/u/notes");
    expect(projectKeyOf({})).toBeNull();
  });

  it("lists projects most recent first, the folder-less bucket last, archived excluded", () => {
    const projects = deriveProjects([
      row("a", 100, { git_repo_root: "/u/alpha" }),
      row("b", 300, { cwd: "/u/beta" }),
      row("c", 200, { git_repo_root: "/u/alpha", cwd: "/u/alpha/x" }),
      row("d", 900),
      row("e", 999, { cwd: "/u/gamma", archived: true }),
    ]);
    expect(projects.map((p) => [p.path, p.label, p.count])).toEqual([
      ["/u/beta", "beta", 1],
      ["/u/alpha", "alpha", 2],
      [null, "", 1],
    ]);
  });

  it("adds the parent folder only when two projects share a name", () => {
    const projects = deriveProjects([row("a", 1, { cwd: "/u/one/app" }), row("b", 2, { cwd: "/u/two/app" }), row("c", 3, { cwd: "/u/web" })]);
    const labels = disambiguatedLabels(projects);
    expect(labels.get("/u/one/app")).toBe("one/app");
    expect(labels.get("/u/two/app")).toBe("two/app");
    expect(labels.get("/u/web")).toBe("web");
  });

  it("membership is the exact folder key, not a name prefix", () => {
    expect(inProject(row("a", 1, { cwd: "/a/hermes-remote" }), "/a/hermes")).toBe(false);
    expect(inProject(row("a", 1, { git_repo_root: "/a/hermes", cwd: "/a/hermes/sub" }), "/a/hermes")).toBe(true);
    expect(inProject(row("a", 1), null)).toBe(true);
  });

  // HG-182: the Web chat subtitle showed the default project's folder basename (e.g. 「bs」 for
  // /Users/bs). Android `projectLabelOfPath` returns null there so the subtitle can spell out
  // 「默认项目」 instead; the label function owns that rule.
  it("names a folder by its basename unless it is the default project", () => {
    expect(projectLabelOfPath("/u/hermes-remote", "/Users/bs")).toBe("hermes-remote");
    expect(projectLabelOfPath("/Users/bs/", "/Users/bs")).toBeNull();
    expect(projectLabelOfPath("/Users/bs", "/Users/bs/")).toBeNull();
    expect(projectLabelOfPath(null, "/Users/bs")).toBeNull();
    expect(projectLabelOfPath("/Users/bs", null)).toBe("bs");
  });

  it("recognises the default project path ignoring trailing slashes and blanks", () => {
    expect(isDefaultProjectPath("/Users/bs/", "/Users/bs")).toBe(true);
    expect(isDefaultProjectPath("/Users/bsx", "/Users/bs")).toBe(false);
    expect(isDefaultProjectPath("  ", "/Users/bs")).toBe(false);
    expect(isDefaultProjectPath("/Users/bs", null)).toBe(false);
  });
});
