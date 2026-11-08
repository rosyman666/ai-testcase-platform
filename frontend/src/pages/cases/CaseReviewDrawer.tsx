/**
 * 用例评审抽屉。详情 + steps 编辑 + 保存（走 PUT，D-50）+ 评审（A/E）。
 *
 * ⚠️ **保存只提交用户真正改过的字段**（传 `null` = "不动"），
 * 全量回传会把服务器上别人刚改的字段覆盖回去。
 */

import { useState, useEffect } from 'react';
import Drawer from '@mui/material/Drawer';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import TextField from '@mui/material/TextField';
import IconButton from '@mui/material/IconButton';
import CloseIcon from '@mui/icons-material/Close';
import Divider from '@mui/material/Divider';
import Chip from '@mui/material/Chip';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import StatusChip from '@/components/common/StatusChip';
import RejectReasonPicker from '@/pages/cases/RejectReasonPicker';
import { getCase, updateCase, reviewCase } from '@/api/cases';
import { toast } from '@/utils/toast';
import type { TestCaseVO, CaseUpdateReq, ReviewReq } from '@/types/cases';

/** CaseReviewDrawer 属性。 */
export interface CaseReviewDrawerProps {
  /** 用例 ID（null = 关闭）。 */
  caseId: string | null;
  /** 关闭回调。 */
  onClose: () => void;
  /** 评审完成回调（自动下一条用）。 */
  onReviewed?: (updated: TestCaseVO) => void;
}

/**
 * 用例评审抽屉。
 *
 * @param props 属性
 * @returns 抽屉
 */
export default function CaseReviewDrawer({ caseId, onClose, onReviewed }: CaseReviewDrawerProps): JSX.Element {
  const queryClient = useQueryClient();
  const [title, setTitle] = useState('');
  const [precondition, setPrecondition] = useState('');
  const [expected, setExpected] = useState('');
  const [stepsText, setStepsText] = useState('');
  const [rejectReasonCode, setRejectReasonCode] = useState('');
  const [showRejectPicker, setShowRejectPicker] = useState(false);
  const [saving, setSaving] = useState(false);

  const { data: caseData, isLoading } = useQuery({
    queryKey: ['case', caseId],
    queryFn: () => getCase(caseId!),
    enabled: !!caseId,
  });

  // 填充表单
  useEffect(() => {
    if (caseData) {
      setTitle(caseData.title);
      setPrecondition(caseData.precondition);
      setExpected(caseData.expected);
      setStepsText(
        caseData.steps.map((s) => `${s.action}${s.data ? ` | ${s.data}` : ''}`).join('\n'),
      );
      setRejectReasonCode('');
      setShowRejectPicker(false);
    }
  }, [caseData]);

  /** 收集用户修改过的字段（null = 不动）。 */
  const collectChanges = (): CaseUpdateReq => {
    if (!caseData) return {};
    const req: CaseUpdateReq = {};
    if (title !== caseData.title) req.title = title;
    if (precondition !== caseData.precondition) req.precondition = precondition;
    if (expected !== caseData.expected) req.expected = expected;
    const newSteps = stepsText.split('\n').filter((l) => l.trim()).map((line) => {
      const [action, data] = line.split('|').map((s) => s.trim());
      return { action: action ?? '', data: data ?? '' };
    });
    const origSteps = caseData.steps.map((s) => ({ action: s.action, data: s.data }));
    if (JSON.stringify(newSteps) !== JSON.stringify(origSteps)) {
      req.steps = newSteps;
    }
    return req;
  };

  const hasChanges = Object.keys(collectChanges()).length > 0;

  const handleSave = async (): Promise<void> => {
    if (!caseId) return;
    const req = collectChanges();
    if (Object.keys(req).length === 0) {
      toast('没有任何字段发生变化，无需保存；若原文通过请直接采纳', 'warning');
      return;
    }
    setSaving(true);
    try {
      const updated = await updateCase(caseId, req);
      queryClient.setQueryData(['case', caseId], updated);
      toast('已保存', 'success');
      onReviewed?.(updated);
    } catch {
      // http.ts 已统一 toast
    } finally {
      setSaving(false);
    }
  };

  const handleAccept = async (): Promise<void> => {
    if (!caseId) return;
    setSaving(true);
    try {
      const updated = await reviewCase(caseId, { action: 'ACCEPT' });
      queryClient.setQueryData(['case', caseId], updated);
      toast('已采纳', 'success');
      onReviewed?.(updated);
    } catch {
      // http.ts 已统一 toast
    } finally {
      setSaving(false);
    }
  };

  const handleReject = async (): Promise<void> => {
    if (!caseId || !rejectReasonCode) return;
    setSaving(true);
    try {
      const req: ReviewReq = { action: 'REJECT', rejectReasonCode };
      const updated = await reviewCase(caseId, req);
      queryClient.setQueryData(['case', caseId], updated);
      toast('已驳回', 'success');
      onReviewed?.(updated);
    } catch {
      // http.ts 已统一 toast
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer anchor="right" open={!!caseId} onClose={onClose} sx={{ '& .MuiDrawer-paper': { width: 480 } }}>
      <Box className="flex h-full flex-col">
        {/* 头部 */}
        <Box className="flex items-center justify-between p-4">
          <Typography variant="h6">用例详情</Typography>
          <IconButton onClick={onClose}>
            <CloseIcon />
          </IconButton>
        </Box>
        <Divider />

        {isLoading || !caseData ? (
          <Box className="flex-1 p-4">加载中…</Box>
        ) : (
          <>
            {/* 内容区 */}
            <Box className="flex-1 overflow-y-auto p-4">
              <Box className="mb-3 flex items-center gap-2">
                <StatusChip code={caseData.status} />
                <Chip label={caseData.priority} size="small" variant="outlined" />
                <Chip label={caseData.caseType} size="small" variant="outlined" />
              </Box>

              <TextField
                label="标题"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                fullWidth
                multiline
                rows={2}
                className="mb-3"
              />
              <TextField
                label="前置条件"
                value={precondition}
                onChange={(e) => setPrecondition(e.target.value)}
                fullWidth
                multiline
                rows={2}
                className="mb-3"
              />
              <TextField
                label="步骤（每行一步，用 | 分隔动作与入参）"
                value={stepsText}
                onChange={(e) => setStepsText(e.target.value)}
                fullWidth
                multiline
                rows={6}
                className="mb-3"
              />
              <TextField
                label="预期结果"
                value={expected}
                onChange={(e) => setExpected(e.target.value)}
                fullWidth
                multiline
                rows={3}
                className="mb-3"
              />

              {/* 评审历史 */}
              {caseData.reviews.length > 0 && (
                <Box>
                  <Typography variant="subtitle2" className="mb-1">
                    评审历史（{caseData.reviews.length} 条）
                  </Typography>
                  {caseData.reviews.map((r) => (
                    <Box key={r.id} className="mb-1 rounded border border-gray-100 p-2">
                      <Typography variant="caption" color="text.secondary">
                        {r.action} · {r.createdAt}
                        {r.rejectReasonCode && ` · ${r.rejectReasonCode}`}
                      </Typography>
                      {r.reasonText && (
                        <Typography variant="body2">{r.reasonText}</Typography>
                      )}
                    </Box>
                  ))}
                </Box>
              )}

              {/* 驳回原因选择 */}
              {showRejectPicker && (
                <Box className="mt-4">
                  <RejectReasonPicker
                    value={rejectReasonCode}
                    onChange={setRejectReasonCode}
                    onConfirm={handleReject}
                  />
                </Box>
              )}
            </Box>

            {/* 底部操作 */}
            <Divider />
            <Box className="flex items-center justify-between p-4">
              <Button
                onClick={() => setShowRejectPicker(!showRejectPicker)}
                color="error"
                disabled={saving}
              >
                {showRejectPicker ? '取消驳回' : '驳回 (R)'}
              </Button>
              <Box className="flex gap-2">
                <Button
                  onClick={handleSave}
                  variant="outlined"
                  disabled={saving || !hasChanges}
                >
                  保存修改
                </Button>
                <Button
                  onClick={handleAccept}
                  variant="contained"
                  disabled={saving}
                >
                  采纳 (A)
                </Button>
              </Box>
            </Box>
          </>
        )}
      </Box>
    </Drawer>
  );
}
