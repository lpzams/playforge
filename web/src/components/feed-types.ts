/** Shapes of PLAYTEST_RESULT checks and REVIEW_RESULT issues inside event payloads. */
export interface Check { name: string; passed: boolean; detail?: string }
export interface ReviewIssue { severity: string; file: string | null; line: number | null; message: string }
