package com.example.todo.userapi.service;

import com.example.todo.auth.TokenProvider;
import com.example.todo.auth.TokenUserInfo;
import com.example.todo.exception.NoRegisteredArgumentsException;
import com.example.todo.userapi.dto.request.LoginRequestDTO;
import com.example.todo.userapi.dto.request.UserRequestSignUpDTO;
import com.example.todo.userapi.dto.response.KakaoUserDTO;
import com.example.todo.userapi.dto.response.LoginResponseDTO;
import com.example.todo.userapi.dto.response.UserSignUpResponseDTO;
import com.example.todo.userapi.entity.Role;
import com.example.todo.userapi.entity.User;
import com.example.todo.userapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;

    @Value("${kakao.client_id}")
    private String KAKAO_CLIENT_ID;

    @Value("${kakao.redirect_url}")
    private String KAKAO_REDIRECT_URI;

    @Value("${kakao.client_secret}")
    private String KAKAO_CLIENT_SECRET;

    @Value("${upload.path}")
    private String uploadRootPath;

    // 회원 가입 처리
    public UserSignUpResponseDTO create(
            final UserRequestSignUpDTO dto,
            final String uploadFilePath
    ) {
        String email = dto.getEmail();
        if(isDuplicate(email)) {
            log.info("이메일이 중복되었습니다. - {}", email);
            throw new RuntimeException("중복된 이메일 입니다.");
        }

        // 패스워드 인코딩
        String encoded = passwordEncoder.encode(dto.getPassword());
        dto.setPassword(encoded);

        // dto를 User Entity로 변환해서 저장
        User saved = userRepository.save(dto.toEntity(uploadFilePath));
        log.info("회원 가입 정상 수행됨 - saved user - {}", saved);

        return new UserSignUpResponseDTO(saved);

    }

    public boolean isDuplicate(String email) {
        return userRepository.existsByEmail(email);
    }

    // 회원 인증
    public LoginResponseDTO authenticate(final LoginRequestDTO dto){
        // 이메일을 통해 회원 정보 조회
        User user = userRepository.findByEmail(dto.getEmail())
                .orElseThrow(
                        () -> new RuntimeException("가입된 회원이 아닙니다.")
                );

        // 패스워드 검증
        String rawPassword = dto.getPassword(); // 입력한 비번
        String encodedPassword = user.getPassword();
        if (!passwordEncoder.matches(rawPassword, encodedPassword)){
            throw new RuntimeException("비밀번호가 일치하지 않습니다.");
        }

        log.info("{}님 로그인 성공!", user.getUserName());

        // 로그인 성공 후에 클라이언트에게 무엇을 리턴할 것인가.
        //-> JWT를 클라이언트에게 발급해 주어야 한다.
        String token = tokenProvider.createToken(user);
        return new LoginResponseDTO(user, token);
    }

    // 프리미엄으로 등급 업
    public LoginResponseDTO promoteToPremium(TokenUserInfo userInfo) {

        User foundUser = userRepository.findById(userInfo.getUserId())
                .orElseThrow(
                        () -> new NoRegisteredArgumentsException("회원 조회에 실패했습니다.")
                );

        // 일반회원(COMMON)이 아니라면 예외 발생
//        if(userInfo.getRole() != Role.COMMON) {
//            throw new IllegalArgumentException("일반 회원이 아니라면 등급을 상승시킬 수 없습니다.");
//        }

        // 등급 변경
        foundUser.changeRole(Role.PREMIUM);
        User saved = userRepository.save(foundUser);

        // 토큰 재발급 (새롭게 변경된 정보로)
        String token = tokenProvider.createToken(saved);

        return new LoginResponseDTO(saved, token);
    }

    /**
     * 업로드된 파일을 서버에 저장하고 저장 경로를 리턴.
     *
     * @param profileImg 업로드된 파일의 정보
     * @return 실제로 저장된 이미지 경로
     */
    public String uploadProfileImage(MultipartFile profileImg) throws IOException {

        // 루트 디렉토리가 실존하는지 확인 후 존재하지 않으면 생성.
        File rootDir = new File(uploadRootPath);
        if(!rootDir.exists()) rootDir.mkdirs();

        // 파일명을 유니크하게 변경 (이름 충돌 가능성을 대비)
        // UUID와 원본파일명을 혼합 -> 규칙 없음.
        String uniqueFileName = UUID.randomUUID() + "_" + profileImg.getOriginalFilename();

        // 파일 저장
        File uploadFile = new File((uploadRootPath + "/" + uniqueFileName));
        profileImg.transferTo(uploadFile);

        return uniqueFileName;

    }

    public String findProfilePath(String userId) {

        User user = userRepository.findById(userId).orElseThrow();
        // DB에 저장되는 profile_img는 파일명 -> service가 가지고 있는 Root Path와 연결해서 리턴.
       String profileImg = user.getProfileImg();
        if(profileImg.startsWith("http")){
            return profileImg;
        }

        return uploadRootPath + "/" + profileImg;

    }

    public LoginResponseDTO kakaoService(final String code) {
        // 인가 코드를 통해 토큰 발급받기
        Map<String, Object> responseData = getKakaoAccessToken(code);
        log.info("token: {}", responseData.get("access_token"));

        // 토큰을 이용해 사용자 정보 가져오기
        KakaoUserDTO dto = getKakaoUserInfo((String) responseData.get("access_token"));

        // 일회성 로그인으로 처리 -> dto를 바로 화면단으로 리턴
        // 회원가입 처리 -> 이메일 중복 검사 진행 -> 자체 jwt 생성해서 토큰을 화면단에 리턴.
        // -> 화면단에서는 적절한 url을 선택하여 redirect를 진행.

        if(!isDuplicate(dto.getKakaoAccount().getEmail())) {
            // 이메일이 중복되지 않았다 -> 이전에 로그인한 적이 없음 -> DB에 데이터 세팅
            User saved = userRepository.save(dto.toEntity((String) responseData.get("access_token")));
            userRepository.save(saved);
        }
        // 이메일이 중복 -> 이전에 로그인한 적이 있다. -> DB에 데이터를 또 넣을 필요 없다.

        User foundUser = userRepository.findByEmail(dto.getKakaoAccount().getEmail()).orElseThrow();
        String token = tokenProvider.createToken(foundUser);

        foundUser.setAccessToken((String) responseData.get("access_token"));
        userRepository.save(foundUser);

        return new LoginResponseDTO(foundUser, token);


    }

    private KakaoUserDTO getKakaoUserInfo(String accessToken) {

        // 요청 uri
        String requestUri = "https://kapi.kakao.com/v2/user/me";

        // 요청 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        // 요청 보내기
        RestTemplate template = new RestTemplate();
        ResponseEntity<KakaoUserDTO> responseEntity = template.exchange(requestUri, HttpMethod.GET, new HttpEntity<>(headers), KakaoUserDTO.class);

        // 응답 바디 읽기
        KakaoUserDTO responseData = responseEntity.getBody();
        log.info("user profile: {}", responseData);

        return responseData;
    }

    private Map<String, Object> getKakaoAccessToken(String code) {

        // 요청 uri
        String requestUri = "https://kauth.kakao.com/oauth/token";

        // 요청 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        // 요청 바디(파라미터) 설정
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code"); // 카카오 공식 문서 기준 값으로 세팅
        params.add("client_id", KAKAO_CLIENT_ID); // 카카오 디벨로퍼 REST API 키
        params.add("redirect_uri", KAKAO_REDIRECT_URI); // 카카오 디벨로퍼 redirect uri
        params.add("code", code); // 프론트에서 인가 코드 요청 시 전달받은 코드 값
        params.add("client_secret", KAKAO_CLIENT_SECRET); // 카카오 디벨로퍼 client secret(활성화 시 추가)

        // 헤더와 바디 정보를 합치기 위해 HttpEntity 객체 생성
        HttpEntity<Object> requestEntity = new HttpEntity<>(params, headers);

        // 카카오 서버로 POST 통신
        RestTemplate template = new RestTemplate();

        // 통신을 보내면서 응답데이터를 리턴
        // param1: 요청 url
        // param2: 요청 메서드
        // param3: 헤더와 요청파라미터정보 엔터티
        // param4: 응답데이터를 받을 객체의 타입 (ex: dto, map)
        // 만약 구조가 복잡한 경우에는 응답 데이터 타입을 String으로 받아서
        // JSON-simple 라이브러리로 직접 해체.
        ResponseEntity<Map> responseEntity
                = template.exchange(requestUri, HttpMethod.POST, requestEntity, Map.class);

        // 응답 데이터에서 필요한 정보 가져오기
        Map<String, Object> responseData = (Map<String, Object>) responseEntity.getBody();

        log.info("토큰 요청 응답 데이터: {}", responseData);

        return responseData;


    }

    public String logout(TokenUserInfo userInfo) {
        User foundUser = userRepository.findById(userInfo.getUserId())
                .orElseThrow();

        String accessToken = foundUser.getAccessToken();
        if(foundUser.getAccessToken() != null) {
            String reqUri = "http://kapi.kakao.com/v1/user/logout";
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "Bearer " + accessToken);

            RestTemplate template = new RestTemplate();
            ResponseEntity<String> responseData
                    = template.exchange(reqUri, HttpMethod.POST, new HttpEntity<>(headers), String.class);
            return responseData.getBody();
        }
        return null;
    }
}
