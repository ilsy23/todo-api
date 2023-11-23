package com.example.todo.todoapi.dto.request;

import com.example.todo.todoapi.entity.Todo;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class TodoCreateRequestDTO {

    @NotBlank
    @Size(min = 2, max = 30)
    private String title;

    // dto를 엔터티로 변환
    public Todo toEntity() {
        return Todo.builder()
                .title(this.title)
                .build();
    }

}
