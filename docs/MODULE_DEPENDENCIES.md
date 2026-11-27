# Module Dependencies

## Allowed Dependency Graph

```text
:app ──▶ :feature:home   ──▶ :core:contracts, :core:model, :core:common, :core:designsystem
     ├──▶ :feature:editor ──▶ :core:contracts, :core:model, :core:common, :core:designsystem
     ├──▶ :core:database  ──▶ :core:contracts, :core:model, :core:common
     └──▶ :bridge:jni     ──▶ :core:contracts, :core:model, :core:common
```

## Strict Boundary Rules
- `:core:common` has zero internal module dependencies.
- `:core:model` depends only on `:core:common`.
- `:core:contracts` depends only on `:core:common` and `:core:model`.
- `:feature:home` and `:feature:editor` must never depend on `:core:database`, `:bridge:jni`, or each other.
- `:bridge:jni` and `engine/` must never depend on Compose or Android UI framework widgets.
- `:app` wires concrete implementations explicitly through `AppContainer` without global service locators.
