idf_component_register(SRCS "app_main.c" "cutoff_core.c"
                       INCLUDE_DIRS "."
                       REQUIRES bt nvs_flash esp_driver_gpio esp_timer)
