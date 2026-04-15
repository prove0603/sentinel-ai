package com.zhuangjie.sentinel.scanner;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryWrapperScannerTest {

    private QueryWrapperScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new QueryWrapperScanner();
    }

    /**
     * 测试：内联创建 + 变量后续调用的混合模式。
     * 期望 in 和 eq 合并到同一条 SQL，不产生重复。
     */
    @Test
    void testInlineCreationWithVariableCalls() {
        String code = """
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import com.baomidou.mybatisplus.annotation.TableName;
                import java.util.List;

                @TableName("t_stop_collection")
                class StopCollection {
                    public static final String ID = "id";
                    public static final String STATUS = "status";
                    public static final int STATUS_DELETED = 0;
                    public static final int STATUS_ACTIVE = 1;
                }

                class TestService {
                    void countSuccess(List<Long> ids) {
                        QueryWrapper queryWrapper = new QueryWrapper<StopCollection>().in(StopCollection.ID, ids);
                        queryWrapper.eq(StopCollection.STATUS, StopCollection.STATUS_DELETED);
                    }
                }
                """;

        List<ScannedSql> results = scanner.scanCodeSnippet(code);
        assertEquals(1, results.size(), "should produce exactly 1 SQL (not 2 separate ones)");

        String sql = results.get(0).sqlNormalized();
        System.out.println("Inline+Variable SQL: " + sql);
        assertTrue(sql.contains("IN"), "should contain IN clause");
        assertTrue(sql.contains("status"), "should contain status condition");
        assertFalse(sql.contains("_unknown"), "should not have _unknown table");
    }

    /**
     * 测试：exists 方法应该保留子查询 SQL。
     */
    @Test
    void testExistsWithSubquery() {
        String code = """
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import com.baomidou.mybatisplus.annotation.TableName;

                @TableName("t_user_collector")
                class UserCollector {
                    public static final String ACCOUNT = "account";
                    public static final String STATUS = "status";
                }

                class TestService {
                    void getUser(boolean roleIdCondition, Long roleId) {
                        new QueryWrapper<UserCollector>()
                            .eq(UserCollector.STATUS, 1)
                            .exists(roleIdCondition, "SELECT 1 FROM t_auth_user_role ur WHERE ur.uid = t_user_collector.id AND ur.role_id = {0}", roleId);
                    }
                }
                """;

        List<ScannedSql> results = scanner.scanCodeSnippet(code);
        assertFalse(results.isEmpty(), "should produce at least 1 SQL");

        String sql = results.get(0).sqlNormalized();
        System.out.println("EXISTS SQL: " + sql);
        assertTrue(sql.contains("EXISTS"), "should contain EXISTS");
        assertTrue(sql.contains("t_auth_user_role"), "should preserve subquery content");
        assertFalse(sql.contains("EXISTS (?)"), "should NOT reduce to just EXISTS (?)");
    }

    /**
     * 测试：纯变量式 QueryWrapper（无泛型）使用 NameExpr 常量列名。
     * NameExpr 常量（如静态字段 NAME）能被解析为 snake_case 列名。
     */
    @Test
    void testRawQueryWrapperWithNameExprConstants() {
        String code = """
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

                class TestService {
                    static final String NAME = "name";
                    static final String STATUS = "status";

                    void searchUserName(String namePrefix) {
                        QueryWrapper queryWrapper = new QueryWrapper();
                        queryWrapper.likeRight(NAME, namePrefix);
                        queryWrapper.orderByDesc(STATUS);
                        queryWrapper.last("limit 10");
                    }
                }
                """;

        List<ScannedSql> results = scanner.scanCodeSnippet(code);
        System.out.println("Raw QW results count: " + results.size());
        for (ScannedSql r : results) {
            System.out.println("  Raw QW SQL: " + r.sql());
            System.out.println("  Normalized: " + r.sqlNormalized());
            System.out.println("  Source: " + r.sourceLocation());
        }
        assertFalse(results.isEmpty(), "should produce at least 1 SQL");

        String sql = results.get(0).sqlNormalized();
        System.out.println("Raw QW SQL: " + sql);
        assertTrue(sql.contains("LIKE"), "should contain LIKE clause");
        assertTrue(sql.contains("ORDER BY"), "should contain ORDER BY");
        assertTrue(sql.contains("limit 10"), "should contain last clause");
    }

    /**
     * 测试：Wrappers.lambdaUpdate() 工厂方法 + 变量链式调用多个 set。
     * 模拟 OrgTeamMemberManager.editOrgTeamMember 的真实场景。
     * 期望所有 set 和 eq 条件都被解析出来，而非只有第一个。
     */
    @Test
    void testLambdaUpdateWrapperChainedSets() {
        String code = """
                import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
                import com.baomidou.mybatisplus.core.toolkit.Wrappers;
                import com.baomidou.mybatisplus.annotation.TableName;

                @TableName("t_org_team_member")
                class OrgTeamMember {
                    private Long id;
                    private String procStartTime;
                    private String procEndTime;
                    private Integer maxTaskNum;
                    private Integer maxAssistTaskNum;
                    private Integer dailyAssistApplyTimes;
                    public Long getId() { return id; }
                    public String getProcStartTime() { return procStartTime; }
                    public String getProcEndTime() { return procEndTime; }
                    public Integer getMaxTaskNum() { return maxTaskNum; }
                    public Integer getMaxAssistTaskNum() { return maxAssistTaskNum; }
                    public Integer getDailyAssistApplyTimes() { return dailyAssistApplyTimes; }
                }

                class TestService {
                    void editMember(Long id) {
                        LambdaUpdateWrapper<OrgTeamMember> updateWrapper = Wrappers.lambdaUpdate();
                        updateWrapper
                                .set(OrgTeamMember::getProcStartTime, "09:00")
                                .set(OrgTeamMember::getProcEndTime, "18:00")
                                .set(OrgTeamMember::getMaxTaskNum, 10)
                                .set(OrgTeamMember::getMaxAssistTaskNum, 5)
                                .set(OrgTeamMember::getDailyAssistApplyTimes, 3)
                                .eq(OrgTeamMember::getId, id);
                    }
                }
                """;

        List<ScannedSql> results = scanner.scanCodeSnippet(code);
        System.out.println("LambdaUpdate results count: " + results.size());
        for (ScannedSql r : results) {
            System.out.println("  SQL: " + r.sql());
            System.out.println("  Type: " + r.sqlType());
        }
        assertEquals(1, results.size(), "should produce exactly 1 SQL");

        String sql = results.get(0).sql();
        System.out.println("LambdaUpdate SQL: " + sql);
        assertEquals("UPDATE", results.get(0).sqlType(), "should be UPDATE type");
        assertTrue(sql.contains("t_org_team_member"), "should resolve table name");
        assertTrue(sql.contains("proc_start_time"), "should contain proc_start_time SET");
        assertTrue(sql.contains("proc_end_time"), "should contain proc_end_time SET");
        assertTrue(sql.contains("max_task_num"), "should contain max_task_num SET");
        assertTrue(sql.contains("max_assist_task_num"), "should contain max_assist_task_num SET");
        assertTrue(sql.contains("daily_assist_apply_times"), "should contain daily_assist_apply_times SET");
        assertTrue(sql.contains("WHERE"), "should contain WHERE clause");
        assertTrue(sql.contains("id = ?"), "should contain id eq condition");

        long setCount = sql.chars().filter(c -> c == ',').count();
        assertTrue(setCount >= 4, "SET clause should have at least 5 columns (4+ commas), got " + (setCount + 1) + " columns");
    }

    /**
     * 测试：LambdaQueryWrapper 变量链式调用（非 new 创建，非内联链）。
     * 类似 queryWrapper.eq(...).in(...).between(...) 多级链式调用。
     */
    @Test
    void testVariableChainedQueryConditions() {
        String code = """
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import com.baomidou.mybatisplus.annotation.TableName;

                @TableName("t_order")
                class Order {
                    public static final String STATUS = "status";
                    public static final String AMOUNT = "amount";
                    public static final String TYPE = "type";
                    public static final String CREATE_TIME = "create_time";
                }

                class TestService {
                    void queryOrders(int status, int type) {
                        QueryWrapper<Order> qw = new QueryWrapper<>();
                        qw.eq(Order.STATUS, status)
                          .eq(Order.TYPE, type)
                          .between(Order.AMOUNT, 100, 999)
                          .orderByDesc(Order.CREATE_TIME);
                    }
                }
                """;

        List<ScannedSql> results = scanner.scanCodeSnippet(code);
        assertEquals(1, results.size(), "should produce exactly 1 SQL");

        String sql = results.get(0).sql();
        System.out.println("Chained variable SQL: " + sql);
        assertTrue(sql.contains("status = ?"), "should have status condition");
        assertTrue(sql.contains("type = ?"), "should have type condition");
        assertTrue(sql.contains("BETWEEN"), "should have BETWEEN clause");
        assertTrue(sql.contains("ORDER BY"), "should have ORDER BY");
    }

    /**
     * 测试：条件参数（boolean prefix）不影响 in 方法解析。
     */
    @Test
    void testInWithConditionPrefix() {
        String code = """
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import com.baomidou.mybatisplus.annotation.TableName;
                import java.util.List;

                @TableName("t_user")
                class User {
                    public static final String PERSON_UUID = "person_uuid";
                    public static final String COMPANY_ID = "company_id";
                    public static final String COUNTRY_ID = "country_id";
                }

                class TestService {
                    void getUserList(String personUuid, List<Long> companyIdList, Integer countryId) {
                        new QueryWrapper<User>()
                            .eq(User.PERSON_UUID, personUuid)
                            .in(companyIdList != null, User.COMPANY_ID, companyIdList)
                            .eq(countryId != null, User.COUNTRY_ID, countryId);
                    }
                }
                """;

        List<ScannedSql> results = scanner.scanCodeSnippet(code);
        assertFalse(results.isEmpty());

        String sql = results.get(0).sqlNormalized();
        System.out.println("IN with condition SQL: " + sql);
        assertTrue(sql.contains("IN"), "should contain IN clause");
        assertTrue(sql.contains("person_uuid"), "should contain person_uuid");
        assertTrue(sql.contains("company_id"), "should contain company_id");
    }
}
