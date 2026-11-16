import unittest
from update.maven import check_maven
from update.model import Component


class MavenTest(unittest.TestCase):
    def test_stable_versions_and_managed_unknown(self):
        item = Component('org.example:library', 'maven-dependency', 'pom.xml', declared='1.2.0')
        check_maven(item, {}, lambda *_: ['1.2.0', '1.3.0', '2.0.0-RC1'])
        self.assertEqual((item.status, item.candidate), ('update-available', '1.3.0'))
        item.declared = '${version}'
        check_maven(item, {}, lambda *_: self.fail('must resolve effective POM first'))
        self.assertEqual(item.status, 'unknown')

    def test_error_is_sanitized(self):
        item = Component('org.example:library', 'maven-dependency', 'pom.xml', declared='1.0.0')
        def fail(*_):
            raise OSError('secret')
        check_maven(item, {}, fail)
        self.assertEqual(item.status, 'unavailable')
        self.assertNotIn('secret', item.detail)

    def test_spring_bom_managed_dependency_points_to_parent(self):
        item = Component('org.springframework.boot:spring-boot-starter-webmvc', 'maven-dependency',
                         'pom.xml', metadata={'effective_version': '4.1.1',
                                              'managed_by': 'org.springframework.boot:spring-boot-dependencies',
                                              'update_owner': 'org.springframework.boot:spring-boot-starter-parent'})
        check_maven(item, {}, lambda *_: self.fail('BOM-managed child must not be checked independently'))
        self.assertEqual(item.status, 'parent-managed')
        self.assertIn('spring-boot-starter-parent', item.detail)


class MavenNumericVersionTest(unittest.TestCase):
    def test_four_component_versions_are_compared_without_misclassifying_qualifiers(self):
        item = Component('org.xerial:sqlite-jdbc', 'maven-dependency', 'pom.xml', declared='3.53.2.1')
        check_maven(item, {}, lambda *_: ['3.53.2.1', '3.53.2.2', '3.54.0-RC1'])
        self.assertEqual((item.status, item.candidate), ('update-available', '3.53.2.2'))
