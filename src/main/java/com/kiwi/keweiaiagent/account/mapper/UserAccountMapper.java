package com.kiwi.keweiaiagent.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kiwi.keweiaiagent.account.entity.UserAccountDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户账号 MyBatis Plus 数据访问接口，统一使用项目现有的 MySQL SqlSessionFactory。
 */
public interface UserAccountMapper extends BaseMapper<UserAccountDO> {

    /**
     * 按固定主键顺序锁定全部正常 admin。角色和状态修改事务必须先调用本方法，确保多个
     * 并发管理操作串行复核“至少保留一个正常 admin”，并避免以不同目标账号作为首锁导致死锁。
     */
    @Select("""
            SELECT id
            FROM ai_user_account
            WHERE role = 'ADMIN' AND status = 'ACTIVE'
            ORDER BY id
            FOR UPDATE
            """)
    List<Long> lockActiveAdminIds();

    /**
     * 锁定并读取目标账号的最新数据，后续业务校验和更新均在同一 MySQL 事务内完成。
     */
    @Select("SELECT * FROM ai_user_account WHERE id = #{accountId} FOR UPDATE")
    UserAccountDO selectByIdForUpdate(@Param("accountId") Long accountId);

    /**
     * 使用页面读取到的 version 更新角色；版本不一致时返回 0，由服务层转换为并发冲突。
     */
    @Update("""
            UPDATE ai_user_account
            SET role = #{role}, version = version + 1, update_time = CURRENT_TIMESTAMP(3)
            WHERE id = #{accountId} AND version = #{version}
            """)
    int updateRoleWithVersion(
            @Param("accountId") Long accountId,
            @Param("role") String role,
            @Param("version") Long version
    );

    /**
     * 使用页面读取到的 version 更新状态；版本不一致时拒绝覆盖其他管理员刚完成的修改。
     */
    @Update("""
            UPDATE ai_user_account
            SET status = #{status}, version = version + 1, update_time = CURRENT_TIMESTAMP(3)
            WHERE id = #{accountId} AND version = #{version}
            """)
    int updateStatusWithVersion(
            @Param("accountId") Long accountId,
            @Param("status") String status,
            @Param("version") Long version
    );
}
