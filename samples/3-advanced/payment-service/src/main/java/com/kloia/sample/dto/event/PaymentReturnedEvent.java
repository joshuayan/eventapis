package com.kloia.sample.dto.event;

import com.kloia.eventapis.common.EventType;
import com.kloia.eventapis.common.PublishedEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentReturnedEvent extends PublishedEvent {
    private String orderId;
    private float amount;

    @Override
    public EventType getEventType() {
        return EventType.OP_SINGLE;
    }
}
