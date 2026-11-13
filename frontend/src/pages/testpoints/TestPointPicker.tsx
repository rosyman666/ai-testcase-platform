/**
 * 测试点评审落地页（`/testpoints`）。
 *
 * 为什么需要这一页：测试点评审的路由是 `/testpoints/:docId`——评审对象永远是"某一篇文档
 * 抽出来的测试点"，没有 docId 就没有落脚页。此前唯一入口是文档详情页里的一个按钮
 * （`pages/docs/DocDetail.tsx`），导致侧栏第 6 个业务模块点不进去。本页把"选文档"
 * 这一步显式化：**先选文档，再进评审**。
 *
 * 本页只负责"选文档进入评审"，不提供批量抽取 / 删除等写操作——那些属于文档管理，
 * 留在 `/docs`，免得两个页面都能改同一份数据、责任说不清。
 */

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Container from '@mui/material/Container';
import Paper from '@mui/material/Paper';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TablePagination from '@mui/material/TablePagination';
import TableRow from '@mui/material/TableRow';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import dayjs from 'dayjs';
import { listDocs } from '@/api/doc';
import EmptyState from '@/components/common/EmptyState';
import PageHeader from '@/components/common/PageHeader';
import { DOC_TYPE_LABEL, getParseStatusMeta } from '@/constants/docStatus';
import type { DocListQuery } from '@/types/doc';

/**
 * 测试点评审落地页。
 *
 * @returns 文档选择器
 */
export default function TestPointPicker(): JSX.Element {
  const navigate = useNavigate();
  const [query, setQuery] = useState<DocListQuery>({ page: 1, size: 10 });
  const [keywordInput, setKeywordInput] = useState('');

  // queryKey 带上完整 query 对象：react-query 会做结构化哈希，
  // 翻页/搜索自动视为不同缓存条目，不必手写拼串
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['docs', query],
    queryFn: () => listDocs(query),
  });

  const rows = data?.records ?? [];

  /** 提交搜索：页码必须回到 1，否则在第 3 页搜一个只有 1 页结果的关键词会直接空列表。 */
  const submitKeyword = (): void => {
    setQuery((prev) => ({ ...prev, page: 1, keyword: keywordInput.trim() }));
  };

  return (
    <Container maxWidth="lg" sx={{ py: 4 }}>
      <PageHeader
        title="测试点评审"
        subtitle="选择一个文档，查看并评审它抽取出的测试点"
        actions={
          <Button variant="contained" onClick={() => navigate('/docs')}>
            去上传文档
          </Button>
        }
      />

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Box className="flex flex-wrap items-center gap-2">
          <TextField
            size="small"
            placeholder="按文件名搜索"
            value={keywordInput}
            onChange={(event) => setKeywordInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                submitKeyword();
              }
            }}
            inputProps={{ 'aria-label': '按文件名搜索' }}
            sx={{ minWidth: 240 }}
          />
          <Button variant="outlined" onClick={submitKeyword}>
            查询
          </Button>
          <Button
            onClick={() => {
              setKeywordInput('');
              setQuery({ page: 1, size: query.size });
            }}
          >
            重置
          </Button>
        </Box>
      </Paper>

      <Paper variant="outlined">
        {isLoading ? (
          <EmptyState variant="loading" title="加载文档列表…" />
        ) : isError ? (
          <EmptyState
            variant="error"
            title="文档列表加载失败"
            description="请确认后端服务可用，然后重试。"
            action={
              <Button variant="outlined" onClick={() => void refetch()}>
                重试
              </Button>
            }
          />
        ) : rows.length === 0 ? (
          <EmptyState
            variant="empty"
            title="还没有文档"
            description="先上传一份需求文档，解析完成后就能在这里进入测试点评审。"
            action={
              <Button variant="contained" onClick={() => navigate('/docs')}>
                去上传
              </Button>
            }
          />
        ) : (
          <>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>文件名</TableCell>
                  <TableCell>类型</TableCell>
                  <TableCell>解析状态</TableCell>
                  <TableCell align="right">章节数</TableCell>
                  <TableCell align="right">接口数</TableCell>
                  <TableCell>更新时间</TableCell>
                  <TableCell align="right">操作</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row) => {
                  const statusMeta = getParseStatusMeta(row.parseStatus);
                  // 只有解析成功的文档才有测试点可评审
                  const reviewable = row.parseStatus === 'PARSED';
                  return (
                    <TableRow key={row.id} hover>
                      <TableCell>{row.fileName}</TableCell>
                      <TableCell>{DOC_TYPE_LABEL[row.docType] ?? row.docType}</TableCell>
                      <TableCell>
                        <Chip size="small" label={statusMeta.label} color={statusMeta.color} />
                      </TableCell>
                      <TableCell align="right">{row.sectionCount}</TableCell>
                      <TableCell align="right">{row.operationCount}</TableCell>
                      <TableCell>{dayjs(row.updatedAt).format('YYYY-MM-DD HH:mm')}</TableCell>
                      <TableCell align="right">
                        {reviewable ? (
                          <Button
                            size="small"
                            variant="outlined"
                            onClick={() => navigate(`/testpoints/${row.id}`)}
                          >
                            进入评审
                          </Button>
                        ) : (
                          // disabled 的 <button> 不派发鼠标事件，MUI Tooltip 是监听子元素事件来显示气泡的，
                          // 直接挂在 disabled 按钮上会永远不弹。必须外包一层可接收事件的 <span>。
                          <Tooltip title="文档尚未解析完成">
                            <span>
                              <Button size="small" variant="outlined" disabled>
                                进入评审
                              </Button>
                            </span>
                          </Tooltip>
                        )}
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
            <TablePagination
              component="div"
              count={data?.total ?? 0}
              page={query.page - 1}
              rowsPerPage={query.size}
              rowsPerPageOptions={[10, 20, 50]}
              onPageChange={(_event, nextPage) =>
                setQuery((prev) => ({ ...prev, page: nextPage + 1 }))
              }
              onRowsPerPageChange={(event) =>
                setQuery((prev) => ({ ...prev, page: 1, size: Number(event.target.value) }))
              }
            />
          </>
        )}
      </Paper>
    </Container>
  );
}
