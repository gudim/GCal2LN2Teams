from setuptools import setup, find_packages
from pathlib import Path

# Читаем содержимое README
this_directory = Path(__file__).parent
long_description = (this_directory / "README.md").read_text(encoding="utf-8")

# Читаем зависимости
requirements = (this_directory / "requirements.txt").read_text().splitlines()
requirements = [r for r in requirements if r and not r.startswith('#') and not r.startswith('-')]

setup(
    name="gcaltolotus",
    version="1.0.0",
    author="Your Name",
    author_email="your.email@example.com",
    description="Кроссплатформенная синхронизация Google Calendar и HCL Lotus Notes",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/yourusername/gcaltolotus",
    packages=find_packages(where="src"),
    package_dir={"": "src"},
    classifiers=[
        "Development Status :: 3 - Alpha",
        "Intended Audience :: End Users/Desktop",
        "Topic :: Office/Business :: Scheduling",
        "License :: OSI Approved :: MIT License",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
        "Operating System :: Microsoft :: Windows",
        "Operating System :: POSIX :: Linux",
    ],
    python_requires=">=3.8",
    install_requires=requirements,
    entry_points={
        "console_scripts": [
            "gcalsync=src.main:cli",
        ],
    },
    include_package_data=True,
    package_data={
        "": ["config/*.yaml.example", "config/*.json.example"],
    },
)
