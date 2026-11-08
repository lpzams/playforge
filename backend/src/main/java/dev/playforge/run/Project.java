package dev.playforge.run;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("projects")
public class Project {
    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    private String prompt;
    private Instant createdAt;
}
