/**
 * 驳回原因选择器（受控组件）。不自己提交，只通知 `onConfirm`。
 */

import { useQuery } from '@tanstack/react-query';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import { fetchRejectReasons } from '@/api/cases';

/** RejectReasonPicker 属性。 */
export interface RejectReasonPickerProps {
  /** 当前选中的原因码。 */
  value: string;
  /** 选择回调。 */
  onChange: (code: string) => void;
  /** 确认驳回回调。 */
  onConfirm: () => void;
  /** 是否显示确认按钮。 */
  showConfirm?: boolean;
}

/**
 * 驳回原因选择器。
 *
 * @param props 属性
 * @returns 选择器
 */
export default function RejectReasonPicker({
  value,
  onChange,
  onConfirm,
  showConfirm = true,
}: RejectReasonPickerProps): JSX.Element {
  const { data: reasons, isLoading } = useQuery({
    queryKey: ['reject-reasons'],
    queryFn: () => fetchRejectReasons(true),
  });

  if (isLoading) {
    return <CircularProgress size={20} />;
  }

  return (
    <Box>
      <Typography variant="subtitle2" className="mb-2">
        选择驳回原因
      </Typography>
      <Box className="flex flex-wrap gap-2">
        {(reasons ?? []).map((r) => (
          <Chip
            key={r.code}
            label={r.label}
            clickable
            color={value === r.code ? 'error' : 'default'}
            variant={value === r.code ? 'filled' : 'outlined'}
            onClick={() => onChange(r.code)}
          />
        ))}
      </Box>
      {showConfirm && (
        <Box className="mt-3">
          <Chip
            label="确认驳回"
            clickable
            color="error"
            onClick={onConfirm}
            disabled={!value}
          />
        </Box>
      )}
    </Box>
  );
}
