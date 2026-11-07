package com.aittest.testpoint.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * 测试点的幂等身份：{@code content_hash = sha256(doc_id + title + category)}。
 *
 * <p><b>为什么需要它</b>：验收 ④ 要求"同一文档抽取两次，测试点数量不翻倍"。AI 抽取是**非确定性**的——
 * 同一份文档喂两次，模型完全可能返回措辞不同但实质相同的点。若只按 {@code docId} 判重，
 * 就永远无法区分"这是新点"与"这是刚才那个点"；若全表删掉重抽，人工修订过的描述会被一起冲掉。
 * 唯一索引 {@code uk_test_point_doc_hash} + 这个哈希，把"同一件事"变成一个可判定的二元问题。</p>
 *
 * <p><b>参与哈希的三个字段是有意挑的</b>（DDL 注释的原话是 {@code sha256(doc_id + title + category)}）：</p>
 * <ul>
 *   <li><b>必须进</b>：{@code title} 是测试点的"是什么"，{@code category} 是"哪一类"，{@code docId}
 *       把哈希限定在单篇文档内（两篇文档抽出同名点是正常的，不该互相去重）。</li>
 *   <li><b>坚决不进</b>：{@code description}。它会被人工修订，掺进哈希后"改了一句话"就变成"换了一个点"，
 *       重复抽取时旧点判成新点插入，唯一索引又会把它拦成 {@code 1062}——于是改过描述的文档
 *       <b>再也抽不动了</b>。同理不进 {@code riskLevel}（人会调）与 {@code sourceRef}（纯展示冗余）。</li>
 * </ul>
 *
 * <p><b>分隔符为什么用 {@code \0}</b>：三个字段直接拼接会发生边界碰撞
 * （{@code docId=1, title="2x"} 与 {@code docId=12, title="x"} 拼出来一样），必须分隔。
 * 用 {@code \0} 而不是 {@code \n} 或 {@code |}，是因为后两者都可能出现在标题里。
 * 万一标题里真出现 {@code \0}，后果是"两个不同的点被当成同一个"——少抽一个点，
 * 不会像反过来那样把数据写错。</p>
 *
 * <p><b>标题只 trim，不压缩内部空白</b>：首尾空白是 JSON 序列化噪声（模型爱写 {@code "创建用户 "}），
 * 不 trim 会让同一个点在重抽时算成两个；但内部空白是措辞的一部分
 * （{@code "创建 用户"} 与 {@code "创建用户"} 可能是两条不同的点），压缩会把两条并掉。</p>
 *
 * <p><b>关于重复造轮子</b>：{@code DocService.sha256Prefix} / {@code TokenService} /
 * {@code MockLlmProvider} 各自都手写了一份 SHA-256。这里是第四份，
 * <b>刻意不顺手去合并</b>——那三处的用途（文件指纹前 32 位 / 签名 / 选样）与本处（唯一键，64 位全量）
 * 的截断策略和编码都不同，合并要连带改三处调用点与它们的既有断言，属于独立的清理任务。</p>
 *
 * @author 寇豆码
 */
public final class TestPointHasher {

    /** 唯一换算算法：DDL 注释写的就是 SHA-256，改算法必须配迁移脚本重建唯一索引。 */
    private static final String ALGORITHM = "SHA-256";

    /** 字段分隔符（NUL）。理由见类注释。 */
    private static final char SEPARATOR = '\0';

    private TestPointHasher() {
    }

    /**
     * 计算 {@code content_hash}。
     *
     * @param docId    所属文档 ID（不可为 null）
     * @param title    测试点标题（会被 {@code trim()}）
     * @param category 分类编码（会被 {@code trim()}）
     * @return 64 位小写十六进制字符串
     * @throws IllegalArgumentException 任一入参为 {@code null}，或 trim 后为空
     */
    public static String hashOf(Long docId, String title, String category) {
        Objects.requireNonNull(docId, "docId 不能为 null");
        String safeTitle = normalize(title, "title");
        String safeCategory = normalize(category, "category");

        String material = docId + String.valueOf(SEPARATOR) + safeTitle + SEPARATOR + safeCategory;
        return toHex(digest(material.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 归一化入参：去首尾空白，空白/空串视为缺失。
     *
     * @param value     原值
     * @param fieldName 字段名（异常文案用）
     * @return 归一化后的值
     */
    private static String normalize(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为 null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return trimmed;
    }

    /**
     * 计算摘要。
     *
     * @param bytes 原文
     * @return 32 字节摘要
     */
    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance(ALGORITHM).digest(bytes);
        } catch (NoSuchAlgorithmException ex) {
            // JDK 一定带 SHA-256；走到这里说明运行环境被动过手脚，不能吞掉
            throw new IllegalStateException("运行环境缺少 " + ALGORITHM + "，无法计算测试点去重键", ex);
        }
    }

    /**
     * 字节数组转小写十六进制。
     *
     * @param bytes 原文
     * @return 64 位十六进制（32 字节 × 2）
     */
    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
