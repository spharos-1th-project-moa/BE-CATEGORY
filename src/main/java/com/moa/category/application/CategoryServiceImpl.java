package com.moa.category.application;

import com.moa.category.domain.CategoryMeetingList;
import com.moa.category.domain.MeetingThemeCategory;
import com.moa.category.domain.UserInterestList;
import com.moa.category.dto.CategoryMeetingGetDto;
import com.moa.category.dto.UserInterestGetDto;
import com.moa.category.infrastructure.CategoryMeetingListRepository;
import com.moa.category.infrastructure.ThemeCategoryRepository;
import com.moa.category.infrastructure.UserInterestRepository;
import com.moa.category.vo.response.CategoriesListOut;
import com.moa.category.vo.request.CreateThemeCategoryIn;
import com.moa.global.config.exception.CustomException;
import com.moa.global.config.exception.ErrorCode;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryServiceImpl implements CategoryService{
    private final JPAQueryFactory query;
    private final ThemeCategoryRepository themeCategoryRepository;
    private final UserInterestRepository userInterestRepository;
    private final CategoryMeetingListRepository categoryMeetingListRepository;


    // 카테고리 선택 화면을 위해서 상위카테고리들아래에 하위카데고리들을 리스트로 묶어서 보내는 코드
    @Transactional(readOnly = true)
    @Override
    public List<CategoriesListOut> categoriesList() {
        List<MeetingThemeCategory> themeCategories = themeCategoryRepository.findAllByCategoryNotDeleted();

        Map<Integer, List<MeetingThemeCategory>> groupedData = themeCategories.stream()
                .filter(tc -> tc.getTopCategory() != null && tc.getId() != null)
                .collect(Collectors.groupingBy(tc -> tc.getTopCategory().getId()));

        return groupedData.entrySet().stream()
                .map(entry -> {
                    Integer topCategoryId = entry.getKey();
                    List<MeetingThemeCategory> subCategoryList = entry.getValue();

                    MeetingThemeCategory topCategory = subCategoryList.get(0).getTopCategory();
                    List<CategoriesListOut.SubCategory> subCategories = subCategoryList.stream()
                            .map(tc -> new CategoriesListOut.SubCategory(tc.getId(), tc.getCategoryName()))
                            .collect(Collectors.toList());

                    // 해당 상위 카테고리에 포함되는 모임의 개수를 조회
                    Integer meetingCount = categoryMeetingListRepository
                            .countByTopCategoryIdAndEnableIsTrue(topCategoryId);

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
    public void createMeetingCategory(CategoryMeetingGetDto categoryMeetingGetDto) {
        CategoryMeetingList categoryMeetingList = CategoryMeetingList.builder()
                .topCategoryId(categoryMeetingGetDto.getTopCategoryId())
                .subCategoryId(categoryMeetingGetDto.getSubCategoryId())
                .meetingId(categoryMeetingGetDto.getCategoryMeetingId())
                .build();

        categoryMeetingListRepository.save(categoryMeetingList);
    }

    // 모임 취소, 모임종료, 모임삭제시, enable을 0으로 바꾸는 코드
    @Transactional(readOnly = false)
    @Override
    public void disableMeetingCategory(Long meetingId) {
        CategoryMeetingList categoryMeetingList = categoryMeetingListRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("해당 모임의 카테고리가 존재하지 않습니다."));
        categoryMeetingList.disable();
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


    // 카테고리 선택시 그에 맞는 모임 리스트로 보여주는 코드
    @Transactional(readOnly = true)
    @Override
    public List<Long> getMeetingListByCategory(int categoryId) {
        List<CategoryMeetingList> categoryMeetingLists;
        MeetingThemeCategory category = themeCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new CustomException(ErrorCode.BAD_REQUEST));
        if (category.getTopCategory() == null) {
            categoryMeetingLists = categoryMeetingListRepository.findByTopCategoryIdAndEnableIsTrue(categoryId);
        } else {
            categoryMeetingLists = categoryMeetingListRepository.findBySubCategoryIdAndEnableIsTrue(categoryId);
        }

        return categoryMeetingLists.stream()
                .map(CategoryMeetingList::getMeetingId)
                .collect(Collectors.toList());
    }

}

