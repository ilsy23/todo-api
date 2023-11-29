package com.example.todo.todoapi.service;

import com.example.todo.auth.TokenUserInfo;
import com.example.todo.todoapi.dto.request.TodoCreateRequestDTO;
import com.example.todo.todoapi.dto.request.TodoModifyRequestDTO;
import com.example.todo.todoapi.dto.response.TodoDetailResponseDTO;
import com.example.todo.todoapi.dto.response.TodoListResponseDTO;
import com.example.todo.todoapi.entity.Todo;
import com.example.todo.todoapi.repository.TodoRepository;
import com.example.todo.userapi.entity.Role;
import com.example.todo.userapi.entity.User;
import com.example.todo.userapi.repository.UserRepository;
import com.sun.xml.bind.v2.TODO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TodoService {

    private final TodoRepository todoRepository;
    private final UserRepository userRepository;

    public TodoListResponseDTO update(final TodoModifyRequestDTO requestDTO, final String userId)
            throws RuntimeException {

        Optional<Todo> targetEntity = todoRepository.findById(requestDTO.getId());

        targetEntity.ifPresent(todo -> {
            todo.setDone(requestDTO.isDone());

            todoRepository.save(todo);
        });

        return retrieve(userId);

    }

    public TodoListResponseDTO create (
            final TodoCreateRequestDTO requestDTO,
            final TokenUserInfo userInfo
            )
            throws RuntimeException {

        // 이제 할 일 등록은 회원만 할 수 있도록 세팅하기 떄문에
        // toEntity의 매개값으로 User엔터티도 함꼐 전달해야 한다.
        // -> userId로 회원 엔터티 조회해야 함
        User user = getUser(userInfo.getUserId());

        // 권한에 따른 글쓰기 제한 처리
        // 일반 회원이 일정을 5개 초과해서 작성하면 예외 발생 시킴
        if(userInfo.getRole() == Role.COMMON
                && todoRepository.countByUser(user) >= 5) {
            throw new IllegalStateException("일반회원은 더 이상 일정을 작성할 수 없습니다.");
        }

        todoRepository.save(requestDTO.toEntity(user));
        log.info("할 일 저장 완료. 제목: {}", requestDTO.getTitle());

        return retrieve(userInfo.getUserId());
    }

    // 글 전체 목록
    public TodoListResponseDTO retrieve(String userId) {

        // 로그인한 유저의 정보를 데이터베이스 조회
        User user = getUser(userId);

        List<Todo> entityList = todoRepository.findAllByUser(user);

        List<TodoDetailResponseDTO> dtoList = entityList.stream()
                .map(TodoDetailResponseDTO::new)
                .collect(Collectors.toList());

        return TodoListResponseDTO.builder()
                .todos(dtoList)
                .build();
    }

    private User getUser(String userId) {
        return userRepository.findById(userId).orElseThrow(
                () -> new RuntimeException("회원정보가 없습니다.")
        );
    }


    public TodoListResponseDTO delete(final String todoId, final String userId) {

        try {
            todoRepository.deleteById(todoId);
        } catch (Exception e) {
            log.error("id가 존재하지 않아 삭제에 실패했습니다. - ID: {}, err: {}"
                    , todoId, e.getMessage());
            throw new RuntimeException("id가 존재하지 않아 삭제에 실패했습니다.");
        }
        return retrieve(userId);
    }
}
