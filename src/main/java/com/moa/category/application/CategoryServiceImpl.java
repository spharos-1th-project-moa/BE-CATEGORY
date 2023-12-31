package com.moa.category.application;

import com.moa.category.domain.*;
import com.moa.category.domain.enums.CompanyCategory;
import com.moa.category.dto.CategoryMeetingCreateDto;
import com.moa.category.dto.CategoryMeetingGetDto;
import com.moa.category.dto.MeetingDetailGetDto;
import com.moa.category.dto.UserInterestGetDto;
import com.moa.category.infrastructure.CategoryMeetingListRepository;
import com.moa.category.infrastructure.ThemeCategoryRepository;
import com.moa.category.infrastructure.UserInterestRepository;
import com.moa.category.vo.request.UserCategoriesIn;
import com.moa.category.vo.response.CategoriesListOut;
import com.moa.category.vo.request.CreateThemeCategoryIn;
import com.moa.category.vo.response.MeetingListOut;
import com.moa.global.config.exception.CustomException;
import com.moa.global.config.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import jakarta.persistence.criteria.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryServiceImpl implements CategoryService{
    private final ThemeCategoryRepository themeCategoryRepository;
    private final UserInterestRepository userInterestRepository;
    private final CategoryMeetingListRepository categoryMeetingListRepository;


    public MeetingDetailGetDto getMeetingId(Long id) {
        Optional<CategoryMeetingList> meetingListOpt = categoryMeetingListRepository.findById(id);

        if (!meetingListOpt.isPresent()) {
            throw new IllegalArgumentException("해당 모임이 존재하지 않습니다.");
        }

        CategoryMeetingList meetingList = meetingListOpt.get();

        // 회의가 활성화되어 있는지 확인
        boolean isEnabled = meetingList.getEnable();

        if (!isEnabled) {
            throw new IllegalStateException("이 회의는 현재 활성화되어 있지 않습니다.");
        }

        // Enum에서 title을 가져옵니다.
        String participateGenderTitle = meetingList.getParticipateGender().getTitle();

        MeetingDetailGetDto meetingDetailGetDto = MeetingDetailGetDto.builder()
                .meetingId(meetingList.getMeetingId())
                .maxAge(meetingList.getMaxAge())
                .minAge(meetingList.getMinAge())
                .participateGender(participateGenderTitle) // Enum의 title을 사용합니다.
                .build();

        return meetingDetailGetDto;
    }

    // 카테고리 선택 화면을 위해서 상위카테고리들아래에 하위카데고리들을 리스트로 묶어서 보내는 코드
    @Transactional(readOnly = true)
    @Override
    public List<CategoriesListOut> categoriesList() {
        // 삭제되지 않은 모든 'MeetingThemeCategory'를 데이터베이스에서 조회
        List<MeetingThemeCategory> themeCategories = themeCategoryRepository.findAllByCategoryNotDeleted();

        // 조회된 'MeetingThemeCategory' 목록을 상위 카테고리 ID로 그룹화
        // 이때 상위 카테고리와 ID가 null이 아닌 것들만 필터링
        Map<Integer, List<MeetingThemeCategory>> groupedData = themeCategories.stream()
                .filter(tc -> tc.getTopCategory() != null && tc.getId() != null)
                .collect(Collectors.groupingBy(tc -> tc.getTopCategory().getId()));

        // 그룹화된 데이터를 바탕으로 'CategoriesListOut' 객체 리스트 생성
        return groupedData.entrySet().stream()
                .map(entry -> {
                    Integer topCategoryId = entry.getKey();
                    List<MeetingThemeCategory> subCategoryList = entry.getValue();

                    // 상위 카테고리 객체를 얻음
                    MeetingThemeCategory topCategory = subCategoryList.get(0).getTopCategory();
                    // 하위 카테고리 목록을 생성
                    List<CategoriesListOut.SubCategory> subCategories = subCategoryList.stream()
                            .map(tc -> new CategoriesListOut.SubCategory(tc.getId(), tc.getCategoryName()))
                            .collect(Collectors.toList());

                    // 해당 상위 카테고리에 포함되는 활성화된 미팅의 개수 조회
                    Integer meetingCount = categoryMeetingListRepository
                            .countByTopCategoryIdAndEnableIsTrue(topCategoryId);

                    // 최종적으로 상위 카테고리 ID, 이름, 하위 카테고리 목록, 미팅 수를 포함하는 'CategoriesListOut' 반환
                    return new CategoriesListOut(
                            topCategory.getId(),
                            topCategory.getCategoryName(),
                            subCategories,
                            meetingCount);
                })
                .collect(Collectors.toList());
    }



    // 유저가 선택한 카테고리를 DB에 저장하는 코드
    @Transactional(readOnly = false)
    @Override
    public void createUserInterests(UserInterestGetDto userInterestGetDto) {
        if (userInterestGetDto.getUser_category_id().size() < 5 || userInterestGetDto.getUser_category_id().size() > 10) {
            throw new IllegalArgumentException("주제 카테고리의 수는 최소 5개, 최대 10개여야 합니다.");
            //주제 카테고리 수가 5개 미만이거나 10개 초과일 경우 예외처리
        }

        List<UserInterestList> userInterests = userInterestGetDto.getUser_category_id().stream()
                .map(categoryId -> UserInterestList.builder()
                        .userUuid(userInterestGetDto.getUserUuid())
                        //todo : uuid를 어떻게 처리할지 몰라 일단 header에서 받는걸로 했음. crud후, 누리님과 이야기해서 수정해야함. :security
                        .userCategoryId(categoryId) // 유저가 선택한 카테고리(관심사) id
                        .build())
                .collect(Collectors.toList());
        userInterestRepository.saveAll(userInterests);
    }

    // 관리자가 주제 카테고리 생성
    @Transactional(readOnly = false)
    @Override
    public void createThemeCategory(CreateThemeCategoryIn createThemeCategoryIn) {
        MeetingThemeCategory topCategory = null;

        //상위 카테고리 ID가 제공된 경우, 해당 ID의 카테고리를 사용
        if (createThemeCategoryIn.getTopCategoryId() != null) {
            topCategory = themeCategoryRepository.findById(createThemeCategoryIn.getTopCategoryId())
                    .orElseThrow(() -> new IllegalArgumentException("상위 카테고리가 존재하지 않습니다."));
        }
        // ID가 없고 상위 카테고리 이름이 제공된 경우, 새로운 상위 카테고리를 생성
        else if (createThemeCategoryIn.getTopCategoryName() != null) {
            topCategory = MeetingThemeCategory.builder()
                    .categoryName(createThemeCategoryIn.getTopCategoryName())
                    .categorySoftDelete(true)
                    .build();
            themeCategoryRepository.save(topCategory);
        }

        // 하위 카테고리 이름이 제공된 경우, 상위 카테고리 아래에 새로운 하위 카테고리를 생성
        if (createThemeCategoryIn.getSubCategoryName() != null) {
            if (topCategory == null) {
                throw new IllegalArgumentException("상위 카테고리가 없으면 하위 카테고리를 생성할 수 없습니다.");
            }
            MeetingThemeCategory subCategory = MeetingThemeCategory.builder()
                    .categoryName(createThemeCategoryIn.getSubCategoryName())
                    .categorySoftDelete(true)
                    .topCategory(topCategory)
                    .build();
            themeCategoryRepository.save(subCategory);
        }
    }


    // 모임 생성시 모임의 카테고리를 DB에 저장하는 코드
    @Transactional(readOnly = false)
    @Override
    public void createMeetingCategory(CategoryMeetingCreateDto dto) {
        log.debug("dto : {}", dto.toString());

        MeetingThemeCategory meetingThemeCategory = themeCategoryRepository.findById(dto.getSubCategoryId())
                .orElseThrow(() -> new CustomException(ErrorCode.BAD_REQUEST));
        MeetingThemeCategory topCategory = meetingThemeCategory.getTopCategory(); //상위 카테고리
        Integer topCategoryId = topCategory == null ? null : topCategory.getId(); //상위 카테고리 id


        // 참여가능한 기업 리스트를 코드로 바꾸는 코드
        String participateCompaniesCode = null;
        if(dto.getParticipateCompanies() != null) {
            participateCompaniesCode = Arrays.stream(dto.getParticipateCompanies().split(", "))
                .map(CompanyCategory::valueOf)
                .map(CompanyCategory::getCode)
                .map(Object::toString)
                .collect(Collectors.joining(", "));
        }
        log.debug("participateCompaniesCode : {}", participateCompaniesCode);


        CategoryMeetingList categoryMeetingList = CategoryMeetingList.builder() //카테고리 모임 리스트 생성
                .topCategoryId(topCategoryId)    //상위카테고리 id
                .subCategoryId(dto.getSubCategoryId())    //하위카테고리 id
                .meetingId(dto.getCategoryMeetingId())    //모임 id
                .maxAge(dto.getMaxAge())  //나이 상한선
                .minAge(dto.getMinAge())  //나이 하한선
                .participateGender(dto.getParticipateGender())    //참여가능한 성별
                .participateCompanies(participateCompaniesCode) //참여가능한 기업 리스트
                .enable(true)   //모임 종료, 모임 취소, 모임 삭제시 : 0으로 바꾸기
                .build();
        categoryMeetingListRepository.save(categoryMeetingList);
    }



    // 모임 취소, 모임종료, 모임삭제시, enable을 0으로 바꾸는 코드
    @Transactional(readOnly = false)
    @Override
    public void disableMeetingCategory(Long meetingId) {
        List<CategoryMeetingList> categoryMeetingLists = categoryMeetingListRepository.findByMeetingId(meetingId);  //meetingId로 카테고리 모임 리스트를 가져옴
        for (CategoryMeetingList categoryMeetingList : categoryMeetingLists) {  //카테고리 모임 리스트를 하나씩 가져와서
            categoryMeetingList.disable();  //enable을 0으로 바꿈
        }
    }

    // 유저 관심사 변경
    @Transactional(readOnly = false)
    @Override
    public void updateUserInterests(UserInterestGetDto userInterestGetDto) {
        // UUID로 기존 선택된 카테고리 리스트를 가져옴
        List<UserInterestList> existingInterests = userInterestRepository.findByUserUuid(userInterestGetDto.getUserUuid());

        // 기존 리스트를 ID로 변환
        Set<Integer> existingCategoryIds = existingInterests.stream()
                .map(UserInterestList::getUserCategoryId)
                .collect(Collectors.toSet());

        // 새 카테고리 리스트
        Set<Integer> newUserCategoryIds = new HashSet<>(userInterestGetDto.getUser_category_id());

        // 기존의 선택된 것들 중 새 리스트에 없는 것은 삭제
        existingInterests.stream()
                .filter(interest -> !newUserCategoryIds.contains(interest.getUserCategoryId()))
                .forEach(userInterestRepository::delete);

        // 새로 선택된 것들 중 기존에 없던 것은 추가
        newUserCategoryIds.stream()
                .filter(id -> !existingCategoryIds.contains(id))
                .map(id -> UserInterestList.builder()
                        .userUuid(userInterestGetDto.getUserUuid())
                        .userCategoryId(id)
                        .build())
                .forEach(userInterestRepository::save);
    }


    // 유저가 선택한 카테고리 조회
    @Transactional(readOnly = true)
    @Override
    public List<Integer> getUserInterests(UUID uuid) {
        List<UserInterestList> userInterests = userInterestRepository.findByUserUuid(uuid);
        return userInterests.stream()
                .map(UserInterestList::getUserCategoryId)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @Override
    public MeetingListOut getMeetingListByCategory(UserCategoriesIn userPreferences, int categoryId) {
        Specification<CategoryMeetingList> spec = createSpecification(userPreferences, categoryId);
        List<CategoryMeetingList> meetingLists = categoryMeetingListRepository.findAll(spec);
        List<Long> meetingIds = meetingLists.stream()
                .map(CategoryMeetingList::getMeetingId)
                .collect(Collectors.toList());

        return MeetingListOut.builder()
                .meetingIdList(meetingIds)
                .count(meetingIds.size())
                .build();
    }

    private Specification<CategoryMeetingList> createSpecification(UserCategoriesIn userPreferences, int categoryId) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 나이 필터
            if (userPreferences.getAge() != null) {
                predicates.add(criteriaBuilder.and(
                        criteriaBuilder.lessThanOrEqualTo(root.get("minAge"), userPreferences.getAge()),
                        criteriaBuilder.greaterThanOrEqualTo(root.get("maxAge"), userPreferences.getAge())
                ));
            }

            // 성별 필터
            if (userPreferences.getParticipateGender() != null) {
                predicates.add(criteriaBuilder.equal(root.get("participateGender"), userPreferences.getParticipateGender()));
            }

            // 회사 카테고리 필터
            if (userPreferences.getParticipateCompanies() != null && !userPreferences.getParticipateCompanies().isEmpty()) {
                predicates.add(criteriaBuilder.like(root.get("participateCompanies"), "%" + userPreferences.getParticipateCompanies() + "%"));
            }

            // enable 필드가 true인 데이터만 필터링
            predicates.add(criteriaBuilder.isTrue(root.get("enable")));

            // 카테고리 ID 필터
            if (categoryId > 0) {
                predicates.add(criteriaBuilder.equal(root.get("topCategoryId"), categoryId));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }


}



