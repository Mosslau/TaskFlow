package com.taskflow.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.taskflow.notification.entity.MailRecord;
import org.apache.ibatis.annotations.Mapper;

/** 邮件记录 Mapper */
@Mapper
public interface MailRecordMapper extends BaseMapper<MailRecord> {
}
