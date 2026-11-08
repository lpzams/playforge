package dev.playforge.run;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/** One step of a run. {@code ok}: REVIEW = no blockers, PLAYTEST = passed, otherwise null. */
@Data
@TableName("steps")
public class Step {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private StepType type;
    private int round;
    @TableField("step_trigger")
    private StepTrigger trigger;
    private StepStatus status;
    private Boolean ok;
    private String outputJson;
    private String error;
    private Instant startedAt;
    private Instant finishedAt;
}
