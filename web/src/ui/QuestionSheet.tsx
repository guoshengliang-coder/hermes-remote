import { useState } from "preact/hooks";
import type { Translate } from "../app/i18n";
import {
  currentQuestion,
  isBatch,
  planApprovalAnswer,
  planClarifyAnswer,
  planClarifySkip,
  type AnswerPlan,
  type QuestionState,
} from "../hermes/requests";
import type { ApprovalChoice } from "../hermes/params";

// Approval and clarify cards rising from the bottom over the composer (DESIGN §5.8 / §5.21):
// full-text buttons, one question at a time for a batch.

export interface QuestionSheetProps {
  questions: QuestionState;
  sessionId: string;
  t: Translate;
  onAnswer: (plan: AnswerPlan) => void;
}

export function QuestionSheet({ questions, sessionId, t, onAnswer }: QuestionSheetProps) {
  if (questions.approval) return <ApprovalCardView questions={questions} sessionId={sessionId} t={t} onAnswer={onAnswer} />;
  if (questions.clarify) return <ClarifyCardView questions={questions} sessionId={sessionId} t={t} onAnswer={onAnswer} />;
  return null;
}

function ApprovalCardView({ questions, sessionId, t, onAnswer }: QuestionSheetProps) {
  const card = questions.approval!;
  const answer = (choice: ApprovalChoice) => {
    const plan = planApprovalAnswer(questions, choice, sessionId);
    if (plan) onAnswer(plan);
  };
  const queued = questions.queuedApprovals.length;
  return (
    <section class="sheet" role="dialog" aria-modal="false" aria-labelledby="approval-title">
      <div class="sheet-grip" aria-hidden="true" />
      <h2 class="sheet-kicker warn" id="approval-title">
        {t("HERMES 请求你批准", "HERMES NEEDS YOUR APPROVAL")}
        {queued ? <span class="sheet-queue mono">+{queued}</span> : null}
      </h2>
      {card.description ? <p class="sheet-text">{card.description}</p> : null}
      {card.command ? <pre class="sheet-command mono">{card.command}</pre> : null}
      <div class="sheet-actions">
        <button type="button" class="choice-button primary" onClick={() => answer("once")}>
          {t("允许一次", "Allow once")}
        </button>
        <button type="button" class="choice-button" onClick={() => answer("session")}>
          {t("本会话允许", "Allow for this session")}
        </button>
        {card.allowPermanent ? (
          <button type="button" class="choice-button" onClick={() => answer("always")}>
            {t("始终允许", "Always allow")}
          </button>
        ) : null}
        <button type="button" class="choice-button danger" onClick={() => answer("deny")}>
          {t("拒绝", "Deny")}
        </button>
      </div>
    </section>
  );
}

function ClarifyCardView(props: QuestionSheetProps) {
  const card = props.questions.clarify!;
  const question = currentQuestion(card);
  if (!question) return null;
  // Keyed per question: its local choice/typing state starts fresh for every question.
  return <ClarifyQuestionView key={`${card.requestId}:${question.qid}:${question.question}`} {...props} />;
}

function ClarifyQuestionView({ questions, sessionId, t, onAnswer }: QuestionSheetProps) {
  const card = questions.clarify!;
  const question = currentQuestion(card);
  const [checked, setChecked] = useState<string[]>([]);
  const [typing, setTyping] = useState(false);
  const [text, setText] = useState("");

  if (!question) return null;
  const batch = isBatch(card);
  const index = card.questions.indexOf(question);
  const freeText = typing || question.choices.length === 0;

  const send = (answer: string) => {
    const plan = planClarifyAnswer(questions, answer, sessionId);
    if (plan) onAnswer(plan);
  };
  const skip = () => {
    const plan = planClarifySkip(questions, sessionId);
    if (plan) onAnswer(plan);
  };

  return (
    <section class="sheet" role="dialog" aria-modal="false" aria-labelledby="clarify-title">
      <div class="sheet-grip" aria-hidden="true" />
      <h2 class="sheet-kicker" id="clarify-title">
        {t("HERMES 需要你决定", "HERMES NEEDS YOUR DECISION")}
        {question.multiSelect ? t(" · 可多选", " · MULTI-SELECT") : ""}
        {batch ? <span class="sheet-queue mono">{index + 1}/{card.questions.length}</span> : null}
      </h2>
      <p class="sheet-question">{question.question}</p>
      {freeText ? (
        <form
          class="sheet-free"
          onSubmit={(e) => {
            e.preventDefault();
            if (text.trim()) send(text.trim());
          }}
        >
          <textarea
            class="sheet-input"
            rows={2}
            value={text}
            placeholder={t("输入你的回答…", "Type your answer…")}
            onInput={(e) => setText((e.target as HTMLTextAreaElement).value)}
          />
          <div class="sheet-actions">
            <button type="submit" class="choice-button primary" disabled={!text.trim()}>
              {t("发送回答", "Send answer")}
            </button>
            {question.choices.length ? (
              <button type="button" class="choice-button" onClick={() => setTyping(false)}>
                {t("返回选项", "Back to choices")}
              </button>
            ) : null}
          </div>
        </form>
      ) : (
        <div class="sheet-actions">
          {question.choices.map((choice) =>
            question.multiSelect ? (
              <button
                type="button"
                key={choice}
                role="checkbox"
                aria-checked={checked.includes(choice)}
                class={`choice-button${checked.includes(choice) ? " checked" : ""}`}
                onClick={() => setChecked(checked.includes(choice) ? checked.filter((c) => c !== choice) : [...checked, choice])}
              >
                <span class="check-box" aria-hidden="true">{checked.includes(choice) ? "✓" : ""}</span>
                {choice}
              </button>
            ) : (
              <button type="button" key={choice} class="choice-button" onClick={() => send(choice)}>
                {choice}
              </button>
            ),
          )}
          {question.multiSelect ? (
            <button type="button" class="choice-button primary" disabled={checked.length === 0} onClick={() => send(question.choices.filter((c) => checked.includes(c)).join(", "))}>
              {checked.length ? t(`确认选择（${checked.length} 项）`, `Confirm (${checked.length})`) : t("确认选择", "Confirm choice")}
            </button>
          ) : null}
          <button type="button" class="choice-button" onClick={() => setTyping(true)}>
            {t("其他（自行输入）", "Other (type your answer)")}
          </button>
        </div>
      )}
      <button type="button" class="text-button subtle sheet-skip" onClick={skip}>
        {batch ? t("跳过全部，让 agent 自行判断", "Skip all — let the agent decide") : t("跳过，让 agent 自行判断", "Skip — let the agent decide")}
      </button>
    </section>
  );
}
